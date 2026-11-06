package com.modernstock.transaction;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.PageResponse;
import com.modernstock.money.BalanceService;
import com.modernstock.report.ReportService;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;

/**
 * Every document of one side in one list (PLAIN.md 12.2 "Transactions"): customers (quotations, sales orders,
 * deliveries, invoices and debit notes, sales receipts, payments received and refunds, returns, credit notes) or suppliers
 * (purchase orders, goods receipts, bills, payments and refunds, returns, supplier credits). Newest first. Each kind is
 * only included for members who may open it, and only from the branches they see. Payments taken at the POS for a sale
 * paid in full belong to that sale and are left out.
 * <p>
 * Native SQL is not filtered by @TenantId, so every part has business_id by hand.
 */
@Service
@RequiredArgsConstructor
public class TransactionService {

    public enum Side { CUSTOMER, SUPPLIER }

    public record TransactionRow(
            @Schema(description = "QUOTATION, SALES_ORDER, DELIVERY, INVOICE, DEBIT_NOTE, SALES_RECEIPT, PAYMENT_RECEIVED, REFUND, "
                    + "SALES_RETURN, CREDIT_NOTE; PURCHASE_ORDER, GOODS_RECEIPT, BILL, SUPPLIER_PAYMENT, PURCHASE_RETURN, SUPPLIER_CREDIT")
            String type,
            Long id, String number, LocalDate date, Long partyId, String partyName, Long branchId, BigDecimal total,
            @Schema(description = "Still to pay (invoices, bills) or to use (credits); empty for the rest") BigDecimal balance,
            String status,
            @Schema(description = "Due date (invoices, bills), valid until (quotations), delivery or expected date (orders)")
            LocalDate dueDate,
            boolean overdue) {
    }

    public record BranchBalance(Long branchId, String branchName, BigDecimal balance) {
    }

    public record PartySummary(Side side, Long partyId,
                               @Schema(description = "Owed now, USD: what the customer owes, or what is owed to the supplier") BigDecimal balance,
                               @Schema(description = "The part of it past its due date") BigDecimal overdue,
                               int overdueCount,
                               @Schema(description = "Sales (customer) or bills (supplier) since 1 January") BigDecimal thisYear,
                               @Schema(description = "Open documents and unused money per branch; the opening balance is not in a branch")
                               List<BranchBalance> branches,
                               BigDecimal openingBalance) {
    }

    /** One kind of document: its key in ?view=, who may see it, and its rows as SQL. */
    private record Kind(String view, List<String> permissions, String sql) {
    }

    private static final String DAY = " at time zone 'Asia/Phnom_Penh')::date";

    /** party: the column holding the customer or supplier; {party} is replaced by the party filter. */
    private static final List<Kind> CUSTOMER_KINDS = List.of(
            new Kind("quotations", List.of("sales_order:view", "sales_order:manage", "delivery:manage"), """
                    select 'QUOTATION' as type, x.id, x.number, x.quote_date as day, x.created_at as at, x.customer_id as party_id,
                           c.name as party_name, x.branch_id, x.total, null::numeric as balance, x.status, x.valid_until as due_date
                      from quotations x join customers c on c.id = x.customer_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.customer_id}"""),
            new Kind("sales-orders", List.of("sales_order:view", "sales_order:manage", "delivery:manage"), """
                    select 'SALES_ORDER', x.id, x.number, x.order_date, x.created_at, x.customer_id, c.name, x.branch_id, x.total,
                           null::numeric, x.status, x.delivery_date
                      from sales_orders x join customers c on c.id = x.customer_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.customer_id}"""),
            new Kind("deliveries", List.of("delivery:manage", "sale:create"), """
                    select 'DELIVERY', x.id, x.number, (x.delivered_at""" + DAY + """
                           , x.created_at, x.customer_id, c.name, x.branch_id,
                           (select i.total from invoices i where i.business_id = x.business_id and i.delivery_id = x.id order by i.id limit 1),
                           null::numeric, x.status, null::date
                      from deliveries x left join customers c on c.id = x.customer_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.customer_id}"""),
            new Kind("invoices", List.of("sale:create", "customer:view"), """
                    select case when x.type = 'DEBIT_NOTE' then 'DEBIT_NOTE' else 'INVOICE' end, x.id, x.number, x.invoice_date,
                           x.created_at, x.customer_id, c.name, x.branch_id, x.total,
                           case when x.status = 'VOID' then 0 else x.total - x.paid_amount end, x.status, x.due_date
                      from invoices x join customers c on c.id = x.customer_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.customer_id}"""),
            new Kind("sales-receipts", List.of("sale:create"), """
                    select 'SALES_RECEIPT', x.id, x.number, (x.sold_at""" + DAY + """
                           , x.created_at, x.customer_id, c.name, x.branch_id, x.total,
                           null::numeric, x.status, null::date
                      from sales_receipts x left join customers c on c.id = x.customer_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.customer_id}"""),
            new Kind("payments", List.of("sale:create"), """
                    select case when x.direction = 'IN' then 'PAYMENT_RECEIVED' else 'REFUND' end, x.id, x.number,
                           (x.paid_at""" + DAY + """
                           , x.created_at, x.party_id, c.name, x.branch_id, x.amount_usd, null::numeric, x.status, null::date
                      from payments x left join customers c on c.id = x.party_id and x.party_type = 'CUSTOMER'
                     where x.business_id = :business and x.branch_id in (:branches) and x.party_type in ('CUSTOMER', 'WALK_IN')
                       and not exists (select 1 from allocations a where a.from_type = 'PAYMENT' and a.from_id = x.id
                                          and a.to_type = 'SALES_RECEIPT') {party:x.party_id}"""),
            new Kind("returns", List.of("sale:void", "customer:view", "sales_return:request"), """
                    select 'SALES_RETURN', x.id, x.number, (x.returned_at""" + DAY + """
                           , x.created_at, x.customer_id, c.name, x.branch_id, x.total,
                           null::numeric, x.status, null::date
                      from sales_returns x left join customers c on c.id = x.customer_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.customer_id}"""),
            new Kind("credits", List.of("sale:void", "customer:view"), """
                    select 'CREDIT_NOTE', x.id, x.number, (x.issued_at""" + DAY + """
                           , x.created_at, x.customer_id, c.name, x.branch_id, x.amount,
                           case when x.status = 'OPEN' then x.amount - x.used_amount - x.refunded_amount else 0 end, x.status, null::date
                      from credit_notes x left join customers c on c.id = x.customer_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.customer_id}"""));

    private static final List<Kind> SUPPLIER_KINDS = List.of(
            new Kind("purchase-orders", List.of("purchase_order:view"), """
                    select 'PURCHASE_ORDER' as type, x.id, x.number, x.order_date as day, x.created_at as at, x.supplier_id as party_id,
                           s.name as party_name, x.branch_id, x.total, null::numeric as balance, x.status, x.expected_date as due_date
                      from purchase_orders x join suppliers s on s.id = x.supplier_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.supplier_id}"""),
            new Kind("goods-receipts", List.of("purchase_order:view", "goods_receipt:create"), """
                    select 'GOODS_RECEIPT', x.id, x.number, (x.received_at""" + DAY + """
                           , x.created_at, po.supplier_id, s.name, x.branch_id,
                           (select b.total from bills b where b.business_id = x.business_id and b.goods_receipt_id = x.id order by b.id limit 1),
                           null::numeric, x.status, null::date
                      from goods_receipts x join purchase_orders po on po.id = x.purchase_order_id join suppliers s on s.id = po.supplier_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:po.supplier_id}"""),
            new Kind("bills", List.of("bill:manage"), """
                    select 'BILL', x.id, x.number, x.bill_date, x.created_at, x.supplier_id, s.name, x.branch_id, x.total,
                           case when x.status = 'VOID' then 0 else x.total - x.paid_amount end, x.status, x.due_date
                      from bills x join suppliers s on s.id = x.supplier_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.supplier_id}"""),
            new Kind("payments", List.of("bill:manage"), """
                    select case when x.direction = 'OUT' then 'SUPPLIER_PAYMENT' else 'REFUND' end, x.id, x.number,
                           (x.paid_at""" + DAY + """
                           , x.created_at, x.party_id, s.name, x.branch_id, x.amount_usd, null::numeric, x.status, null::date
                      from payments x join suppliers s on s.id = x.party_id
                     where x.business_id = :business and x.branch_id in (:branches) and x.party_type = 'SUPPLIER' {party:x.party_id}"""),
            new Kind("returns", List.of("bill:manage", "purchase_return:prepare"), """
                    select 'PURCHASE_RETURN', x.id, x.number, (x.returned_at""" + DAY + """
                           , x.created_at, x.supplier_id, s.name, x.branch_id, x.total,
                           null::numeric, x.status, null::date
                      from purchase_returns x join suppliers s on s.id = x.supplier_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.supplier_id}"""),
            new Kind("credits", List.of("bill:manage"), """
                    select 'SUPPLIER_CREDIT', x.id, x.number, (x.issued_at""" + DAY + """
                           , x.created_at, x.supplier_id, s.name, x.branch_id, x.amount,
                           case when x.status = 'OPEN' then x.amount - x.used_amount - x.refunded_amount else 0 end, x.status, null::date
                      from supplier_credits x join suppliers s on s.id = x.supplier_id
                     where x.business_id = :business and x.branch_id in (:branches) {party:x.supplier_id}"""));

    private static final Set<String> OVERDUE_TYPES = Set.of("INVOICE", "DEBIT_NOTE", "BILL");

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchScope scope;
    private final BalanceService balances;

    /** The type views this member may see on a side, in menu order. */
    public List<String> views(Side side) {
        return kinds(side).stream().filter(TransactionService::allowed).map(Kind::view).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionRow> list(Side side, String view, Long partyId, String status, String q, Long branchId,
                                             LocalDate from, LocalDate to, int page, int size) {
        List<Kind> chosen = kinds(side).stream().filter(TransactionService::allowed)
                .filter(k -> view == null || view.isBlank() || k.view().equals(view)).toList();
        if (chosen.isEmpty()) {
            if (view != null && !view.isBlank() && kinds(side).stream().anyMatch(k -> k.view().equals(view))) {
                throw ApiException.forbidden("PERMISSION_DENIED");
            }
            return page(List.of(), page, size, 0);
        }
        List<Long> branches = scope.branchIds();
        if (branchId != null) {
            BusinessContext.requireBranch(branchId);
            branches = branches.contains(branchId) ? List.of(branchId) : List.of();
        }
        if (branches.isEmpty()) {
            return page(List.of(), page, size, 0);
        }
        StringBuilder union = new StringBuilder();
        for (Kind k : chosen) {
            if (!union.isEmpty()) {
                union.append("\nunion all\n");
            }
            union.append(k.sql().replaceAll("\\{party:([a-z_.]+)}", partyId == null ? "" : " and $1 = :party"));
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("branches", branches)
                .addValue("party", partyId);
        StringBuilder where = new StringBuilder(" where true");
        if (status != null && !status.isBlank()) {
            where.append(" and t.status = :status");
            p.addValue("status", status.trim().toUpperCase());
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (t.number ilike :q or t.party_name ilike :q)");
            p.addValue("q", "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (from != null) {
            where.append(" and t.day >= :from");
            p.addValue("from", from);
        }
        if (to != null) {
            where.append(" and t.day <= :to");
            p.addValue("to", to);
        }
        String base = "from (" + union + ") t(type, id, number, day, at, party_id, party_name, branch_id, total, balance, status, due_date)" + where;
        Long total = jdbc.queryForObject("select count(*) " + base, p, Long.class);
        int safeSize = Math.max(1, Math.min(size, 200));
        p.addValue("limit", safeSize).addValue("offset", (long) Math.max(page, 0) * safeSize);
        LocalDate today = ReportService.today();
        List<TransactionRow> rows = jdbc.query("select t.* " + base + " order by t.day desc, t.at desc, t.id desc limit :limit offset :offset",
                p, (rs, i) -> {
                    Date due = rs.getDate("due_date");
                    BigDecimal balance = rs.getBigDecimal("balance");
                    String type = rs.getString("type");
                    String st = rs.getString("status");
                    LocalDate dueDate = due == null ? null : due.toLocalDate();
                    boolean overdue = OVERDUE_TYPES.contains(type) && !"VOID".equals(st) && dueDate != null
                            && dueDate.isBefore(today) && balance != null && balance.signum() > 0;
                    return new TransactionRow(type, rs.getLong("id"), rs.getString("number"), rs.getDate("day").toLocalDate(),
                            (Long) rs.getObject("party_id", Long.class), rs.getString("party_name"), rs.getLong("branch_id"),
                            rs.getBigDecimal("total"), balance, st, dueDate, overdue);
                });
        return page(rows, Math.max(page, 0), safeSize, total == null ? 0 : total);
    }

    /** The key amounts of one customer or supplier (PLAIN.md 12.2 customer and supplier page). */
    @Transactional(readOnly = true)
    public PartySummary summary(Side side, Long partyId) {
        boolean customer = side == Side.CUSTOMER;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("party", partyId)
                .addValue("branches", scope.branchIds())
                .addValue("today", ReportService.today())
                .addValue("yearStart", ReportService.today().withDayOfYear(1));
        List<BigDecimal> opening = jdbc.queryForList("select opening_balance from " + (customer ? "customers" : "suppliers")
                + " where business_id = :business and id = :party", p, BigDecimal.class);
        if (opening.isEmpty()) {
            throw ApiException.notFound(customer ? "Customer" : "Supplier");
        }
        BigDecimal balance = customer ? balances.customer(partyId)
                : balances.suppliers(List.of(partyId)).getOrDefault(partyId, BigDecimal.ZERO);
        String docs = customer ? "invoices" : "bills";
        String partyCol = customer ? "customer_id" : "supplier_id";
        var overdue = jdbc.queryForMap("select coalesce(sum(total - paid_amount), 0) as amount, count(*) as n from " + docs
                + " where business_id = :business and " + partyCol + " = :party and status in ('UNPAID', 'PARTLY_PAID')"
                + " and due_date < :today", p);
        BigDecimal thisYear = customer
                ? jdbc.queryForObject("""
                        select coalesce((select sum(total) from invoices where business_id = :business and customer_id = :party
                                           and status <> 'VOID' and type <> 'DEBIT_NOTE' and invoice_date >= :yearStart), 0)
                             + coalesce((select sum(total) from sales_receipts where business_id = :business and customer_id = :party
                                           and status = 'POSTED' and (sold_at""" + DAY + """
                         >= :yearStart), 0)""", p, BigDecimal.class)
                : jdbc.queryForObject("""
                        select coalesce(sum(total), 0) from bills where business_id = :business and supplier_id = :party
                           and status <> 'VOID' and bill_date >= :yearStart""", p, BigDecimal.class);
        List<BranchBalance> branches = new ArrayList<>();
        if (!scope.branchIds().isEmpty()) {
            String sql = customer ? """
                    select b.id, coalesce(b.name_en, b.code) as name,
                           coalesce((select sum(i.total - i.paid_amount) from invoices i where i.business_id = b.business_id
                                       and i.branch_id = b.id and i.customer_id = :party and i.status <> 'VOID'), 0)
                         - coalesce((select sum(x.amount_usd - coalesce((select sum(a.amount) from allocations a
                                                                         where a.from_type = 'PAYMENT' and a.from_id = x.id), 0))
                                       from payments x where x.business_id = b.business_id and x.branch_id = b.id
                                        and x.party_type = 'CUSTOMER' and x.party_id = :party and x.direction = 'IN'
                                        and x.status = 'POSTED'), 0)
                         - coalesce((select sum(c.amount - c.used_amount - c.refunded_amount) from credit_notes c
                                      where c.business_id = b.business_id and c.branch_id = b.id and c.customer_id = :party
                                        and c.status = 'OPEN'), 0) as balance
                      from branches b where b.business_id = :business and b.id in (:branches) order by b.id""" : """
                    select b.id, coalesce(b.name_en, b.code) as name,
                           coalesce((select sum(x.total - x.paid_amount) from bills x where x.business_id = b.business_id
                                       and x.branch_id = b.id and x.supplier_id = :party and x.status <> 'VOID'), 0)
                         - coalesce((select sum(x.amount_usd - coalesce((select sum(a.amount) from allocations a
                                                                         where a.from_type = 'PAYMENT' and a.from_id = x.id), 0))
                                       from payments x where x.business_id = b.business_id and x.branch_id = b.id
                                        and x.party_type = 'SUPPLIER' and x.party_id = :party and x.direction = 'OUT'
                                        and x.status = 'POSTED'), 0)
                         - coalesce((select sum(c.amount - c.used_amount - c.refunded_amount) from supplier_credits c
                                      where c.business_id = b.business_id and c.branch_id = b.id and c.supplier_id = :party
                                        and c.status = 'OPEN'), 0) as balance
                      from branches b where b.business_id = :business and b.id in (:branches) order by b.id""";
            jdbc.query(sql, p, rs -> {
                BigDecimal amount = rs.getBigDecimal("balance");
                if (amount.signum() != 0) {
                    branches.add(new BranchBalance(rs.getLong("id"), rs.getString("name"), amount));
                }
            });
        }
        return new PartySummary(side, partyId, balance, (BigDecimal) overdue.get("amount"), ((Number) overdue.get("n")).intValue(),
                thisYear, branches, opening.get(0));
    }

    private static PageResponse<TransactionRow> page(List<TransactionRow> rows, int page, int size, long total) {
        int pages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(rows, page, size, total, pages);
    }

    private static List<Kind> kinds(Side side) {
        return side == Side.CUSTOMER ? CUSTOMER_KINDS : SUPPLIER_KINDS;
    }

    private static boolean allowed(Kind k) {
        return k.permissions().stream().anyMatch(BusinessContext::has);
    }
}
