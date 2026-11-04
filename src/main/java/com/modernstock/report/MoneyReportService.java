package com.modernstock.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * The Should money reports of PLAIN.md 14, for Owners and Managers: cash shifts (over or short, voids), VAT by month,
 * purchases by supplier or product, and customer and supplier statements with a running balance. Native SQL names the
 * business itself (it is not filtered by @TenantId).
 */
@Service
@RequiredArgsConstructor
public class MoneyReportService {

    public record ShiftRow(Long id, String branch, String cashier, Instant openedAt, Instant closedAt,
                           BigDecimal expectedUsd, BigDecimal countedUsd, BigDecimal differenceUsd,
                           BigDecimal expectedKhr, BigDecimal countedKhr, BigDecimal differenceKhr, long voids, BigDecimal voidedValue) {
    }

    public record ShiftReport(LocalDate from, LocalDate to, long shifts, long shortShifts, BigDecimal shortUsd, BigDecimal overUsd,
                              BigDecimal shortKhr, BigDecimal overKhr, long voids, List<ShiftRow> rows) {
    }

    public record VatMonth(String month, BigDecimal taxableSales, BigDecimal salesVat, BigDecimal purchases, BigDecimal purchaseVat,
                           BigDecimal payable) {
    }

    public record VatReport(LocalDate from, LocalDate to, BigDecimal salesVat, BigDecimal purchaseVat, BigDecimal payable,
                            List<VatMonth> rows) {
    }

    public enum PurchasesBy { SUPPLIER, PRODUCT }

    public record PurchaseRow(String key, String label, String labelKm, BigDecimal qty, String unitSymbol, BigDecimal spend,
                              BigDecimal returned, BigDecimal avgCost, long receipts) {
    }

    public record PurchaseReport(LocalDate from, LocalDate to, PurchasesBy by, long receipts, BigDecimal spend, BigDecimal returned,
                                 List<PurchaseRow> rows) {
    }

    public enum Party { CUSTOMER, SUPPLIER }

    public record StatementLine(LocalDate date, String type, String number, BigDecimal debit, BigDecimal credit, BigDecimal balance) {
    }

    public record Statement(Party party, Long partyId, String name, String phone, LocalDate from, LocalDate to, BigDecimal opening,
                            BigDecimal debits, BigDecimal credits, BigDecimal closing, List<StatementLine> lines) {
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchScope scope;

    /** Shifts closed in the period: expected and counted cash in both currencies, the difference, and voided sales. */
    @Transactional(readOnly = true)
    public ShiftReport cashShifts(LocalDate from, LocalDate to) {
        ReportScope.requireMoney();
        List<ShiftRow> rows = jdbc.query("""
                select s.id, b.name_en as branch, u.full_name, s.opened_at, s.closed_at, s.expected_usd, s.counted_usd,
                       s.expected_khr, s.counted_khr,
                       (select count(*) from sales_receipts r where r.cash_shift_id = s.id and r.status = 'VOID') as voids,
                       (select coalesce(sum(r.total), 0) from sales_receipts r where r.cash_shift_id = s.id and r.status = 'VOID') as voided
                  from cash_shifts s join branches b on b.id = s.branch_id join users u on u.id = s.cashier_id
                 where s.business_id = :business and s.status = 'CLOSED' and s.branch_id in (:branches)
                   and (s.closed_at at time zone 'Asia/Phnom_Penh')::date between :from and :to
                 order by s.closed_at desc
                """, period(from, to), (rs, n) -> {
            BigDecimal eu = rs.getBigDecimal("expected_usd");
            BigDecimal cu = rs.getBigDecimal("counted_usd");
            BigDecimal ek = rs.getBigDecimal("expected_khr");
            BigDecimal ck = rs.getBigDecimal("counted_khr");
            return new ShiftRow(rs.getLong("id"), rs.getString("branch"), rs.getString("full_name"), instant(rs.getTimestamp("opened_at")),
                    instant(rs.getTimestamp("closed_at")), eu, cu, diff(cu, eu), ek, ck, diff(ck, ek), rs.getLong("voids"),
                    money(rs.getBigDecimal("voided")));
        });
        return new ShiftReport(from, to, rows.size(),
                rows.stream().filter(r -> neg(r.differenceUsd()) || neg(r.differenceKhr())).count(),
                rows.stream().map(ShiftRow::differenceUsd).filter(MoneyReportService::neg).reduce(ZERO, BigDecimal::add),
                rows.stream().map(ShiftRow::differenceUsd).filter(d -> d != null && d.signum() > 0).reduce(ZERO, BigDecimal::add),
                rows.stream().map(ShiftRow::differenceKhr).filter(MoneyReportService::neg).reduce(ZERO, BigDecimal::add),
                rows.stream().map(ShiftRow::differenceKhr).filter(d -> d != null && d.signum() > 0).reduce(ZERO, BigDecimal::add),
                rows.stream().mapToLong(ShiftRow::voids).sum(), rows);
    }

    /** VAT on sales (returns taken off) and on purchases, month by month: what is left to pay. */
    @Transactional(readOnly = true)
    public VatReport vat(LocalDate from, LocalDate to) {
        ReportScope.requireMoney();
        List<VatMonth> rows = jdbc.query("""
                select to_char(m.day, 'YYYY-MM') as month, sum(m.taxable) as taxable, sum(m.vat) as vat,
                       sum(m.purchases) as purchases, sum(m.purchase_vat) as purchase_vat
                  from (select (r.sold_at at time zone 'Asia/Phnom_Penh')::date as day, r.total - r.vat as taxable, r.vat,
                               0 as purchases, 0 as purchase_vat
                          from sales_receipts r where r.business_id = :business and r.status = 'POSTED' and r.branch_id in (:branches)
                        union all
                        select i.invoice_date, i.total - i.vat, i.vat, 0, 0
                          from invoices i where i.business_id = :business and i.status <> 'VOID' and i.branch_id in (:branches)
                        union all
                        select (sr.returned_at at time zone 'Asia/Phnom_Penh')::date, -(sr.total - sr.vat), -sr.vat, 0, 0
                          from sales_returns sr where sr.business_id = :business and sr.status = 'POSTED' and sr.branch_id in (:branches)
                        union all
                        select b.bill_date, 0, 0, b.total - b.vat, b.vat
                          from bills b where b.business_id = :business and b.status <> 'VOID' and b.branch_id in (:branches)) m
                 where m.day between :from and :to
                 group by to_char(m.day, 'YYYY-MM') order by 1
                """, period(from, to), (rs, n) -> {
            BigDecimal vat = money(rs.getBigDecimal("vat"));
            BigDecimal purchaseVat = money(rs.getBigDecimal("purchase_vat"));
            return new VatMonth(rs.getString("month"), money(rs.getBigDecimal("taxable")), vat, money(rs.getBigDecimal("purchases")),
                    purchaseVat, vat.subtract(purchaseVat));
        });
        BigDecimal sales = rows.stream().map(VatMonth::salesVat).reduce(ZERO, BigDecimal::add);
        BigDecimal purchases = rows.stream().map(VatMonth::purchaseVat).reduce(ZERO, BigDecimal::add);
        return new VatReport(from, to, sales, purchases, sales.subtract(purchases), rows);
    }

    /** Goods received in the period, less what went back to suppliers: spend, quantity and average cost. */
    @Transactional(readOnly = true)
    public PurchaseReport purchases(LocalDate from, LocalDate to, PurchasesBy by) {
        ReportScope.requireMoney();
        String group = by == PurchasesBy.SUPPLIER
                ? "l.supplier_id::text as key, s.name as label, null as label_km, null as unit"
                : "l.product_id::text as key, p.name_en as label, p.name_km as label_km, u.symbol as unit";
        String groupBy = by == PurchasesBy.SUPPLIER ? "l.supplier_id, s.name" : "l.product_id, p.name_en, p.name_km, u.symbol";
        List<PurchaseRow> rows = jdbc.query("""
                select %s, sum(l.base_qty) as qty, sum(l.spend) as spend, sum(l.returned) as returned,
                       count(distinct l.receipt_id) filter (where l.receipt_id is not null) as receipts
                  from (select o.supplier_id, i.product_id, i.base_qty, i.qty * i.unit_cost as spend, 0 as returned, g.id as receipt_id
                          from goods_receipts g join goods_receipt_items i on i.goods_receipt_id = g.id
                          join purchase_orders o on o.id = g.purchase_order_id
                         where g.business_id = :business and g.status = 'POSTED' and g.branch_id in (:branches)
                           and (g.received_at at time zone 'Asia/Phnom_Penh')::date between :from and :to
                        union all
                        select r.supplier_id, ri.product_id, -ri.base_qty, 0, ri.amount, null
                          from purchase_returns r join purchase_return_items ri on ri.return_id = r.id
                         where r.business_id = :business and r.status = 'POSTED' and r.branch_id in (:branches)
                           and (r.returned_at at time zone 'Asia/Phnom_Penh')::date between :from and :to) l
                  join suppliers s on s.id = l.supplier_id
                  join products p on p.id = l.product_id join units u on u.id = p.base_unit_id
                 group by %s
                 order by sum(l.spend) - sum(l.returned) desc
                """.formatted(group, groupBy), period(from, to), (rs, n) -> {
            BigDecimal qty = rs.getBigDecimal("qty");
            BigDecimal spend = money(rs.getBigDecimal("spend"));
            BigDecimal returned = money(rs.getBigDecimal("returned"));
            BigDecimal net = spend.subtract(returned);
            return new PurchaseRow(rs.getString("key"), rs.getString("label"), rs.getString("label_km"),
                    by == PurchasesBy.PRODUCT ? qty : null, rs.getString("unit"), spend, returned,
                    by == PurchasesBy.PRODUCT && qty.signum() > 0 ? net.divide(qty, 4, RoundingMode.HALF_UP) : null,
                    rs.getLong("receipts"));
        });
        long receipts = jdbc.queryForObject("""
                select count(*) from goods_receipts g where g.business_id = :business and g.status = 'POSTED' and g.branch_id in (:branches)
                   and (g.received_at at time zone 'Asia/Phnom_Penh')::date between :from and :to
                """, params(from, to), Long.class);
        return new PurchaseReport(from, to, by, receipts, rows.stream().map(PurchaseRow::spend).reduce(ZERO, BigDecimal::add),
                rows.stream().map(PurchaseRow::returned).reduce(ZERO, BigDecimal::add), rows);
    }

    /**
     * Every document that changed what a customer owes (invoices, refunds up; payments, credit notes down) or what the
     * business owes a supplier (bills, refunds received up; payments, supplier credits down), with a running balance
     * that starts from the balance on the first day. Ends at the same balance as the debt reports.
     */
    @Transactional(readOnly = true)
    public Statement statement(Party party, Long id, LocalDate from, LocalDate to) {
        ReportScope.requireMoney();
        if (to.isBefore(from)) {
            throw ApiException.badRequest("DATE_RANGE_INVALID");
        }
        MapSqlParameterSource p = params(from, to).addValue("party", id);
        String table = party == Party.CUSTOMER ? "customers" : "suppliers";
        List<Map<String, Object>> who = jdbc.queryForList(
                "select name, phone, opening_balance from " + table + " where id = :party and business_id = :business", p);
        if (who.isEmpty()) {
            throw ApiException.notFound(party == Party.CUSTOMER ? "Customer" : "Supplier");
        }
        String events = party == Party.CUSTOMER ? """
                select i.invoice_date as day, 1 as k, i.id, 'INVOICE' as type, i.number, i.total as debit, 0 as credit from invoices i
                 where i.business_id = :business and i.customer_id = :party and i.status <> 'VOID' and i.branch_id in (:branches)
                union all
                select (x.paid_at at time zone 'Asia/Phnom_Penh')::date, 2, x.id, 'PAYMENT', x.number, 0, x.amount_usd from payments x
                 where x.business_id = :business and x.party_type = 'CUSTOMER' and x.party_id = :party and x.direction = 'IN'
                   and x.status = 'POSTED' and x.branch_id in (:branches)
                union all
                select (c.issued_at at time zone 'Asia/Phnom_Penh')::date, 3, c.id, 'CREDIT_NOTE', c.number, 0, c.amount from credit_notes c
                 where c.business_id = :business and c.customer_id = :party and c.status <> 'VOID' and c.branch_id in (:branches)
                union all
                select (x.paid_at at time zone 'Asia/Phnom_Penh')::date, 4, x.id, 'REFUND', x.number, x.amount_usd, 0 from payments x
                 where x.business_id = :business and x.party_type = 'CUSTOMER' and x.party_id = :party and x.direction = 'OUT'
                   and x.status = 'POSTED' and x.branch_id in (:branches)
                """ : """
                select b.bill_date as day, 1 as k, b.id, 'BILL' as type, b.number, b.total as debit, 0 as credit from bills b
                 where b.business_id = :business and b.supplier_id = :party and b.status <> 'VOID' and b.branch_id in (:branches)
                union all
                select (x.paid_at at time zone 'Asia/Phnom_Penh')::date, 2, x.id, 'PAYMENT', x.number, 0, x.amount_usd from payments x
                 where x.business_id = :business and x.party_type = 'SUPPLIER' and x.party_id = :party and x.direction = 'OUT'
                   and x.status = 'POSTED' and x.branch_id in (:branches)
                union all
                select (c.issued_at at time zone 'Asia/Phnom_Penh')::date, 3, c.id, 'SUPPLIER_CREDIT', c.number, 0, c.amount
                  from supplier_credits c
                 where c.business_id = :business and c.supplier_id = :party and c.status <> 'VOID' and c.branch_id in (:branches)
                union all
                select (x.paid_at at time zone 'Asia/Phnom_Penh')::date, 4, x.id, 'REFUND', x.number, x.amount_usd, 0 from payments x
                 where x.business_id = :business and x.party_type = 'SUPPLIER' and x.party_id = :party and x.direction = 'IN'
                   and x.status = 'POSTED' and x.branch_id in (:branches)
                """;
        BigDecimal before = jdbc.queryForObject("select coalesce(sum(e.debit - e.credit), 0) from (" + events + ") e where e.day < :from",
                p, BigDecimal.class);
        BigDecimal opening = money(((BigDecimal) who.get(0).get("opening_balance")).add(before));
        List<StatementLine> lines = new ArrayList<>();
        BigDecimal[] running = {opening};
        jdbc.query("select * from (" + events + ") e where e.day between :from and :to order by e.day, e.k, e.id", p, rs -> {
            BigDecimal debit = money(rs.getBigDecimal("debit"));
            BigDecimal credit = money(rs.getBigDecimal("credit"));
            running[0] = running[0].add(debit).subtract(credit);
            lines.add(new StatementLine(rs.getDate("day").toLocalDate(), rs.getString("type"), rs.getString("number"),
                    debit, credit, running[0]));
        });
        BigDecimal debits = lines.stream().map(StatementLine::debit).reduce(ZERO, BigDecimal::add);
        BigDecimal credits = lines.stream().map(StatementLine::credit).reduce(ZERO, BigDecimal::add);
        return new Statement(party, id, (String) who.get(0).get("name"), (String) who.get(0).get("phone"), from, to, opening,
                debits, credits, running[0], lines);
    }

    /** A period of at most a year, like the other reports (statements may cover longer). */
    private MapSqlParameterSource period(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(366).isBefore(to)) {
            throw ApiException.badRequest("DATE_RANGE_INVALID");
        }
        return params(from, to);
    }

    private MapSqlParameterSource params(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("branches", scope.branchIds())
                .addValue("from", from)
                .addValue("to", to);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static BigDecimal diff(BigDecimal counted, BigDecimal expected) {
        return counted == null || expected == null ? null : counted.subtract(expected);
    }

    private static boolean neg(BigDecimal d) {
        return d != null && d.signum() < 0;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
