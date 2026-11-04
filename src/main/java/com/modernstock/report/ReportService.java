package com.modernstock.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.expense.ExpenseRepository;
import com.modernstock.money.BalanceService;
import com.modernstock.partner.Customer;
import com.modernstock.partner.CustomerRepository;
import com.modernstock.partner.Supplier;
import com.modernstock.partner.SupplierRepository;
import lombok.RequiredArgsConstructor;

/**
 * Sales and money reports (PLAIN.md 14), read straight from the documents. Sales are paid receipts plus invoices
 * (pay later); line revenue is before VAT and the whole-sale discount, cost is the average cost at the moment of sale.
 * A cashier's sales reports cover only their own sales; profit, cost and debts are for Owners and Managers.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    public enum SalesBy { DAY, PRODUCT, CATEGORY, BRANCH, CASHIER }

    public record SalesRow(String key, String label, String labelKm,
                           BigDecimal qty, String unitSymbol, long documents, BigDecimal sales,
                           BigDecimal cost, BigDecimal profit, BigDecimal margin) {
    }

    public record SalesReport(LocalDate from, LocalDate to, SalesBy by, boolean ownSalesOnly, long documents,
                              BigDecimal sales, BigDecimal cost, BigDecimal grossProfit, BigDecimal margin, BigDecimal vat,
                              List<SalesRow> rows) {
    }

    public record ExpenseRow(String category, BigDecimal amount) {
    }

    public record ProfitReport(LocalDate from, LocalDate to, BigDecimal revenue, BigDecimal costOfGoods, BigDecimal grossProfit,
                               BigDecimal expenses, BigDecimal netProfit, List<ExpenseRow> expensesByCategory) {
    }

    public record CustomerDebtRow(Long id, String name, String phone, BigDecimal balance, BigDecimal overdue,
                                  int openInvoices, LocalDate oldestDue) {
    }

    public record CustomerDebtReport(BigDecimal total, BigDecimal overdue, long overdueInvoices, List<CustomerDebtRow> rows) {
    }

    public record SupplierDebtRow(Long id, String name, String phone, BigDecimal balance, BigDecimal dueSoon,
                                  BigDecimal overdue, int openBills, LocalDate nextDue) {
    }

    public record SupplierDebtReport(BigDecimal total, BigDecimal dueSoon, BigDecimal overdue, long overdueBills,
                                     List<SupplierDebtRow> rows) {
    }

    public record AbcRow(Long productId, String sku, String name, String nameKm, BigDecimal qty, String unitSymbol,
                         BigDecimal value, BigDecimal share, BigDecimal cumulative, String group) {
    }

    public record AbcGroup(String group, int products, BigDecimal value, BigDecimal share) {
    }

    public record AbcReport(LocalDate from, LocalDate to, BigDecimal total, List<AbcGroup> groups, List<AbcRow> rows) {
    }

    /**
     * One row per sold line in the period, in the branches the member sees (and only the cashier's own when
     * :cashier is set): paid receipts and invoices.
     */
    static final String LINES = """
            select (r.sold_at at time zone 'Asia/Phnom_Penh')::date as day, r.branch_id, r.cashier_id as user_id,
                   'R' || r.id as doc, si.product_id, si.base_qty,
                   si.qty * si.unit_price - si.discount as revenue, si.base_qty * si.cost_at_sale as cost
              from sale_items si join sales_receipts r on r.id = si.sales_receipt_id
             where r.business_id = :business and r.status = 'POSTED' and r.branch_id in (:branches)
               and (cast(:cashier as bigint) is null or r.cashier_id = :cashier)
               and (r.sold_at at time zone 'Asia/Phnom_Penh')::date between :from and :to
            union all
            select i.invoice_date, i.branch_id, i.created_by, 'I' || i.id, si.product_id, si.base_qty,
                   si.qty * si.unit_price - si.discount, si.base_qty * si.cost_at_sale
              from sale_items si join invoices i on i.delivery_id = si.delivery_id
             where i.business_id = :business and i.status <> 'VOID' and i.branch_id in (:branches)
               and (cast(:cashier as bigint) is null or i.created_by = :cashier)
               and i.invoice_date between :from and :to
            """;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchScope scope;
    private final ExpenseRepository expenses;
    private final CustomerRepository customers;
    private final SupplierRepository suppliers;
    private final BalanceService balances;

    /** Sales summary (by day, product, category, branch or cashier) and, for Owners and Managers, gross profit. */
    @Transactional(readOnly = true)
    public SalesReport sales(LocalDate from, LocalDate to, SalesBy by) {
        ReportScope.requireSales();
        boolean money = ReportScope.money();
        MapSqlParameterSource p = params(from, to);
        String group = switch (by) {
            case DAY -> """
                    select to_char(l.day, 'YYYY-MM-DD') as key, to_char(l.day, 'YYYY-MM-DD') as label, null as label_km,
                           null as qty, null as unit, %s from (%s) l group by l.day order by l.day""";
            case PRODUCT -> """
                    select l.product_id::text as key, p.name_en as label, p.name_km as label_km, sum(l.base_qty) as qty,
                           u.symbol as unit, %s
                      from (%s) l join products p on p.id = l.product_id join units u on u.id = p.base_unit_id
                     group by l.product_id, p.name_en, p.name_km, u.symbol order by sales desc""";
            case CATEGORY -> """
                    select coalesce(c.id::text, '') as key, c.name_en as label, c.name_km as label_km, null as qty,
                           null as unit, %s
                      from (%s) l join products p on p.id = l.product_id left join categories c on c.id = p.category_id
                     group by c.id, c.name_en, c.name_km order by sales desc""";
            case BRANCH -> """
                    select l.branch_id::text as key, b.name_en as label, b.name_km as label_km, null as qty, null as unit, %s
                      from (%s) l join branches b on b.id = l.branch_id
                     group by l.branch_id, b.name_en, b.name_km order by sales desc""";
            case CASHIER -> """
                    select coalesce(l.user_id::text, '') as key, u.full_name as label, null as label_km, null as qty,
                           null as unit, %s
                      from (%s) l left join users u on u.id = l.user_id
                     group by l.user_id, u.full_name order by sales desc""";
        };
        String sums = "count(distinct l.doc) as documents, sum(l.revenue) as sales, sum(l.cost) as cost";
        List<SalesRow> rows = jdbc.query(group.formatted(sums, LINES), p, (rs, n) -> salesRow(rs, money));
        Map<String, Object> totals = jdbc.queryForMap(
                "select count(distinct l.doc) as documents, coalesce(sum(l.revenue), 0) as sales, coalesce(sum(l.cost), 0) as cost from (%s) l"
                        .formatted(LINES), p);
        BigDecimal vat = jdbc.queryForObject("""
                select coalesce((select sum(vat) from sales_receipts r where r.business_id = :business and r.status = 'POSTED'
                          and r.branch_id in (:branches) and (cast(:cashier as bigint) is null or r.cashier_id = :cashier)
                          and (r.sold_at at time zone 'Asia/Phnom_Penh')::date between :from and :to), 0)
                     + coalesce((select sum(vat) from invoices i where i.business_id = :business and i.status <> 'VOID'
                          and i.branch_id in (:branches) and (cast(:cashier as bigint) is null or i.created_by = :cashier)
                          and i.invoice_date between :from and :to), 0)
                """, p, BigDecimal.class);
        BigDecimal sales = money((BigDecimal) totals.get("sales"));
        BigDecimal cost = money((BigDecimal) totals.get("cost"));
        long documents = ((Number) totals.get("documents")).longValue();
        return new SalesReport(from, to, by, !money, documents, sales, money ? cost : null,
                money ? sales.subtract(cost) : null, money ? margin(sales, sales.subtract(cost)) : null, money(vat), rows);
    }

    /** Profit and loss: gross profit minus expenses (Owner and Manager). */
    @Transactional(readOnly = true)
    public ProfitReport profit(LocalDate from, LocalDate to) {
        ReportScope.requireMoney();
        MapSqlParameterSource p = params(from, to);
        Map<String, Object> totals = jdbc.queryForMap(
                "select coalesce(sum(revenue), 0) as revenue, coalesce(sum(cost), 0) as cost from (%s) l".formatted(LINES), p);
        BigDecimal revenue = money((BigDecimal) totals.get("revenue"));
        BigDecimal cost = money((BigDecimal) totals.get("cost"));
        List<ExpenseRow> byCategory = jdbc.query("""
                select c.name_en, sum(e.amount_usd) as amount from expenses e join expense_categories c on c.id = e.category_id
                 where e.business_id = :business and e.status = 'POSTED' and e.branch_id in (:branches)
                   and e.expense_date between :from and :to
                 group by c.name_en order by amount desc
                """, p, (rs, n) -> new ExpenseRow(rs.getString("name_en"), money(rs.getBigDecimal("amount"))));
        BigDecimal spent = money(expenses.totalUsd(scope.branchIds(), from, to));
        BigDecimal gross = revenue.subtract(cost);
        return new ProfitReport(from, to, revenue, cost, gross, spent, gross.subtract(spent), byCategory);
    }

    /** What each customer owes (opening balance included), with the part of their invoices that is past due. */
    @Transactional(readOnly = true)
    public CustomerDebtReport customerDebt() {
        ReportScope.requireMoney();
        List<Customer> all = customers.findAll();
        Map<Long, BigDecimal> owed = balances.customers(all.stream().map(Customer::getId).toList());
        Map<Long, Open> open = open("""
                select customer_id as party, count(*) as docs,
                       sum(case when due_date < :today then total - paid_amount else 0 end) as overdue,
                       0 as due_soon, min(due_date) as first_due,
                       count(*) filter (where due_date < :today) as overdue_docs
                  from invoices where business_id = :business and status in ('UNPAID', 'PARTLY_PAID')
                 group by customer_id
                """);
        List<CustomerDebtRow> rows = all.stream()
                .map(c -> {
                    Open o = open.getOrDefault(c.getId(), Open.NONE);
                    return new CustomerDebtRow(c.getId(), c.getName(), c.getPhone(), owed.getOrDefault(c.getId(), BigDecimal.ZERO),
                            o.overdue(), o.docs(), o.firstDue());
                })
                .filter(r -> r.balance().signum() > 0)
                .sorted(Comparator.comparing(CustomerDebtRow::overdue).thenComparing(CustomerDebtRow::balance).reversed())
                .toList();
        return new CustomerDebtReport(sum(rows.stream().map(CustomerDebtRow::balance)), sum(rows.stream().map(CustomerDebtRow::overdue)),
                open.values().stream().mapToLong(Open::overdueDocs).sum(), rows);
    }

    /** What the business owes each supplier, with bills due in the next 7 days and past due. */
    @Transactional(readOnly = true)
    public SupplierDebtReport supplierDebt() {
        ReportScope.requireMoney();
        List<Supplier> all = suppliers.findAll();
        Map<Long, BigDecimal> owing = balances.suppliers(all.stream().map(Supplier::getId).toList());
        Map<Long, Open> open = open("""
                select supplier_id as party, count(*) as docs,
                       sum(case when due_date < :today then total - paid_amount else 0 end) as overdue,
                       sum(case when due_date between :today and :soon then total - paid_amount else 0 end) as due_soon,
                       min(due_date) filter (where due_date >= :today) as first_due,
                       count(*) filter (where due_date < :today) as overdue_docs
                  from bills where business_id = :business and status in ('UNPAID', 'PARTLY_PAID')
                 group by supplier_id
                """);
        List<SupplierDebtRow> rows = all.stream()
                .map(s -> {
                    Open o = open.getOrDefault(s.getId(), Open.NONE);
                    return new SupplierDebtRow(s.getId(), s.getName(), s.getPhone(), owing.getOrDefault(s.getId(), BigDecimal.ZERO),
                            o.dueSoon(), o.overdue(), o.docs(), o.firstDue());
                })
                .filter(r -> r.balance().signum() > 0)
                .sorted(Comparator.comparing(SupplierDebtRow::overdue).thenComparing(SupplierDebtRow::balance).reversed())
                .toList();
        return new SupplierDebtReport(sum(rows.stream().map(SupplierDebtRow::balance)), sum(rows.stream().map(SupplierDebtRow::dueSoon)),
                sum(rows.stream().map(SupplierDebtRow::overdue)), open.values().stream().mapToLong(Open::overdueDocs).sum(), rows);
    }

    /**
     * ABC analysis: products ranked by the value of what was sold (at cost) in the period. A = the products that make
     * the first 80% of the value, B = the next 15%, C = the last 5%.
     */
    @Transactional(readOnly = true)
    public AbcReport abc(LocalDate from, LocalDate to) {
        ReportScope.requireMoney();
        MapSqlParameterSource p = params(from, to);
        record Sold(Long id, String sku, String name, String nameKm, BigDecimal qty, String unit, BigDecimal value) {
        }
        List<Sold> sold = jdbc.query("""
                select l.product_id, p.sku, p.name_en, p.name_km, sum(l.base_qty) as qty, u.symbol, sum(l.cost) as value
                  from (%s) l join products p on p.id = l.product_id join units u on u.id = p.base_unit_id
                 group by l.product_id, p.sku, p.name_en, p.name_km, u.symbol
                having sum(l.cost) > 0
                 order by value desc
                """.formatted(LINES), p, (rs, n) -> new Sold(rs.getLong("product_id"), rs.getString("sku"),
                rs.getString("name_en"), rs.getString("name_km"), rs.getBigDecimal("qty"), rs.getString("symbol"),
                money(rs.getBigDecimal("value"))));
        BigDecimal total = sum(sold.stream().map(Sold::value));
        List<AbcRow> rows = new ArrayList<>();
        BigDecimal before = BigDecimal.ZERO;
        for (Sold s : sold) {
            BigDecimal share = percent(s.value(), total);
            // A product goes to the group where its value starts: the one that crosses 80% is still A.
            String group = before.compareTo(BigDecimal.valueOf(80)) < 0 ? "A" : before.compareTo(BigDecimal.valueOf(95)) < 0 ? "B" : "C";
            before = before.add(share);
            rows.add(new AbcRow(s.id(), s.sku(), s.name(), s.nameKm(), s.qty(), s.unit(), s.value(), share,
                    before.min(HUNDRED), group));
        }
        List<AbcGroup> groups = List.of("A", "B", "C").stream().map(g -> {
            List<AbcRow> in = rows.stream().filter(r -> r.group().equals(g)).toList();
            BigDecimal value = sum(in.stream().map(AbcRow::value));
            return new AbcGroup(g, in.size(), value, percent(value, total));
        }).toList();
        return new AbcReport(from, to, total, groups, rows);
    }

    private record Open(int docs, BigDecimal overdue, BigDecimal dueSoon, LocalDate firstDue, long overdueDocs) {
        static final Open NONE = new Open(0, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    private Map<Long, Open> open(String sql) {
        LocalDate today = Dates.today();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("today", today)
                .addValue("soon", today.plusDays(7));
        Map<Long, Open> result = new java.util.HashMap<>();
        jdbc.query(sql, p, rs -> {
            java.sql.Date first = rs.getDate("first_due");
            result.put(rs.getLong("party"), new Open(rs.getInt("docs"), money(rs.getBigDecimal("overdue")),
                    money(rs.getBigDecimal("due_soon")), first == null ? null : first.toLocalDate(), rs.getLong("overdue_docs")));
        });
        return result;
    }

    private static SalesRow salesRow(ResultSet rs, boolean money) throws SQLException {
        BigDecimal sales = money(rs.getBigDecimal("sales"));
        BigDecimal cost = money(rs.getBigDecimal("cost"));
        BigDecimal profit = sales.subtract(cost);
        return new SalesRow(rs.getString("key"), rs.getString("label"), rs.getString("label_km"), rs.getBigDecimal("qty"),
                rs.getString("unit"), rs.getLong("documents"), sales, money ? cost : null, money ? profit : null,
                money ? margin(sales, profit) : null);
    }

    MapSqlParameterSource params(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || from.plusDays(366).isBefore(to)) {
            throw ApiException.badRequest("DATE_RANGE_INVALID");
        }
        return new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("branches", scope.branchIds())
                .addValue("cashier", ReportScope.ownCashier(), java.sql.Types.BIGINT)
                .addValue("from", from)
                .addValue("to", to);
    }

    static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Profit as a share of sales, in percent with one decimal. */
    static BigDecimal margin(BigDecimal sales, BigDecimal profit) {
        return percent(profit, sales);
    }

    static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> values) {
        return values.reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Today in Phnom Penh, the default end of a report period. */
    public static LocalDate today() {
        return LocalDate.now(Dates.PHNOM_PENH);
    }
}
