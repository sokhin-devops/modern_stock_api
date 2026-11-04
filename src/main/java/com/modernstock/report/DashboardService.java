package com.modernstock.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.Dates;
import lombok.RequiredArgsConstructor;

/**
 * Dashboard numbers by role (PLAIN.md 12.3), in the selected branch (or all the member sees). Each part is null when
 * the member's role does not see it: sales for Owner, Manager and Cashier (a cashier's own), profit and debts for
 * Owner and Manager, "waiting for me" for whoever approves, receives or counts. Stock cards come from /stock/summary.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    public record TodaySales(BigDecimal usd, BigDecimal khr, long receipts) {
    }

    public record DayPoint(LocalDate day, BigDecimal sales, BigDecimal profit) {
    }

    public record TopProduct(Long productId, String name, String nameKm, BigDecimal qty, String unitSymbol, BigDecimal sales) {
    }

    public record Debts(BigDecimal customersOwe, BigDecimal customersOverdue, long overdueInvoices,
                        BigDecimal weOwe, BigDecimal billsDueSoon, long overdueBills) {
    }

    public record Waiting(Long approvals, Long transfersToReceive, Long countsInProgress, Long countsToApprove) {
    }

    public record DashboardResponse(boolean ownSalesOnly, TodaySales today, BigDecimal profitToday, BigDecimal profitMonth,
                                    List<DayPoint> days, List<TopProduct> topProducts, Debts debts, Waiting waiting) {
    }

    /** Days in the sales chart (the app shows the last 7 or all 30). */
    static final int CHART_DAYS = 30;

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchScope scope;
    private final ReportService reports;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        boolean sales = ReportScope.sales();
        boolean money = ReportScope.money();
        LocalDate today = Dates.today();

        TodaySales todaySales = null;
        List<DayPoint> days = null;
        List<TopProduct> top = null;
        BigDecimal profitToday = null;
        BigDecimal profitMonth = null;
        if (sales) {
            MapSqlParameterSource p = reports.params(today.minusDays(CHART_DAYS - 1L), today);
            todaySales = jdbc.queryForObject("""
                    select coalesce(sum(total), 0) as usd, coalesce(sum(total_khr), 0) as khr, count(*) filter (where sale) as docs from (
                      select r.total, r.total_khr, true as sale from sales_receipts r
                       where r.business_id = :business and r.status = 'POSTED' and r.branch_id in (:branches)
                         and (cast(:cashier as bigint) is null or r.cashier_id = :cashier)
                         and (r.sold_at at time zone 'Asia/Phnom_Penh')::date = :to
                      union all
                      select i.total, i.total_khr, true from invoices i
                       where i.business_id = :business and i.status <> 'VOID' and i.branch_id in (:branches)
                         and (cast(:cashier as bigint) is null or i.created_by = :cashier) and i.invoice_date = :to
                      union all
                      select -sr.total, -round(sr.total * coalesce((select x.usd_to_khr from exchange_rates x
                               where x.business_id = sr.business_id order by x.rate_date desc limit 1), 0)), false
                        from sales_returns sr
                       where sr.business_id = :business and sr.status = 'POSTED' and sr.branch_id in (:branches)
                         and (cast(:cashier as bigint) is null or sr.created_by = :cashier)
                         and (sr.returned_at at time zone 'Asia/Phnom_Penh')::date = :to) d
                    """, p, (rs, n) -> new TodaySales(ReportService.money(rs.getBigDecimal("usd")), rs.getBigDecimal("khr"),
                    rs.getLong("docs")));
            Map<LocalDate, DayPoint> byDay = new HashMap<>();
            jdbc.query("select day, sum(revenue) as sales, sum(cost) as cost from (%s) l group by day".formatted(ReportService.LINES),
                    p, rs -> {
                        BigDecimal s = ReportService.money(rs.getBigDecimal("sales"));
                        BigDecimal c = ReportService.money(rs.getBigDecimal("cost"));
                        LocalDate day = rs.getDate("day").toLocalDate();
                        byDay.put(day, new DayPoint(day, s, money ? s.subtract(c) : null));
                    });
            days = new ArrayList<>();
            for (LocalDate d = today.minusDays(CHART_DAYS - 1L); !d.isAfter(today); d = d.plusDays(1)) {
                days.add(byDay.getOrDefault(d, new DayPoint(d, BigDecimal.ZERO.setScale(2), money ? BigDecimal.ZERO.setScale(2) : null)));
            }
            top = jdbc.query("""
                    select l.product_id, p.name_en, p.name_km, u.symbol, sum(l.base_qty) as qty, sum(l.revenue) as sales
                      from (%s) l join products p on p.id = l.product_id join units u on u.id = p.base_unit_id
                     group by l.product_id, p.name_en, p.name_km, u.symbol order by sales desc limit 10
                    """.formatted(ReportService.LINES), p, (rs, n) -> new TopProduct(rs.getLong("product_id"),
                    rs.getString("name_en"), rs.getString("name_km"), rs.getBigDecimal("qty"), rs.getString("symbol"),
                    ReportService.money(rs.getBigDecimal("sales"))));
            if (money) {
                profitToday = days.get(days.size() - 1).profit();
                MapSqlParameterSource month = reports.params(today.withDayOfMonth(1), today);
                Map<String, Object> m = jdbc.queryForMap(
                        "select coalesce(sum(revenue), 0) as sales, coalesce(sum(cost), 0) as cost from (%s) l".formatted(ReportService.LINES),
                        month);
                profitMonth = ReportService.money((BigDecimal) m.get("sales")).subtract(ReportService.money((BigDecimal) m.get("cost")));
            }
        }

        Debts debts = null;
        if (money) {
            var customers = reports.customerDebt();
            var suppliers = reports.supplierDebt();
            debts = new Debts(customers.total(), customers.overdue(), customers.overdueInvoices(),
                    suppliers.total(), suppliers.dueSoon(), suppliers.overdueBills());
        }

        return new DashboardResponse(sales && !money, todaySales, profitToday, profitMonth, days, top, debts, waiting());
    }

    /** What waits for this member: adjustments and counts to approve, transfers to receive, counts still being counted. */
    Waiting waiting() {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("branches", scope.branchIds())
                .addValue("warehouses", scope.warehouseIds());
        Long approvals = BusinessContext.has("adjustment:approve") ? jdbc.queryForObject("""
                select count(*) from stock_adjustments
                 where business_id = :business and status = 'WAITING_APPROVAL' and branch_id in (:branches)
                """, p, Long.class) : null;
        Long receive = BusinessContext.has("transfer:manage") ? jdbc.queryForObject("""
                select count(*) from stock_transfers
                 where business_id = :business and status = 'IN_TRANSIT' and to_warehouse_id in (:warehouses)
                """, p, Long.class) : null;
        Long counts = BusinessContext.has("count:manage") ? jdbc.queryForObject("""
                select count(*) from stock_counts where business_id = :business and status = 'DRAFT' and branch_id in (:branches)
                """, p, Long.class) : null;
        Long toApprove = BusinessContext.has("adjustment:approve") ? jdbc.queryForObject("""
                select count(*) from stock_counts where business_id = :business and status = 'SUBMITTED' and branch_id in (:branches)
                """, p, Long.class) : null;
        return approvals == null && receive == null && counts == null && toApprove == null ? null
                : new Waiting(approvals, receive, counts, toApprove);
    }
}
