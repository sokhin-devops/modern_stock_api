package com.modernstock.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.catalog.ReorderLevels;
import com.modernstock.common.Dates;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;

/**
 * Stock reports (PLAIN.md 14), read from the stock levels, batches, transfers and counts in the warehouses the member
 * sees. Quantities are in the base unit; each row also names the product's biggest packaging unit, so the app can
 * show boxes and pieces. Values (at average cost) only for members who manage products.
 */
@Service
@RequiredArgsConstructor
public class StockReportService {

    /** Days of average sales a suggested order covers on top of the lead time. */
    static final int COVER_DAYS = 14;
    /** Days of sales the average is taken over. */
    static final int SALES_DAYS = 30;

    public record Pack(String symbol, BigDecimal factor) {
    }

    public record OnHandRow(Long productId, String sku, String name, String nameKm, String category, String unitSymbol,
                            Pack pack, BigDecimal qty, BigDecimal avgCost, BigDecimal value) {
    }

    public record OnHandReport(BigDecimal value, long products, BigDecimal inTransitValue, long transfersInTransit,
                               List<OnHandRow> rows) {
    }

    public record EoqRow(Long productId, String sku, String name, String nameKm, String unitSymbol, Pack pack,
                         @Schema(description = "Base units a year (D): sold in the last year, or the time since the first sale scaled to a year")
                         BigDecimal annualDemand,
                         @Schema(description = "Average cost of one base unit") BigDecimal unitCost,
                         @Schema(description = "√(2 × D × S / H), base units, rounded up") BigDecimal eoq,
                         BigDecimal ordersPerYear,
                         @Schema(description = "Days between two orders of this size") int daysBetweenOrders,
                         BigDecimal onHand) {
    }

    public record EoqReport(@Schema(description = "Days of sales the demand is taken from (30 to 365)") int salesDays,
                            @Schema(description = "S: what one order costs (transport, time), USD") BigDecimal orderCost,
                            @Schema(description = "H as a share of the unit cost per year, %") BigDecimal holdingPercent,
                            List<EoqRow> rows) {
    }

    public record ReorderRow(Long productId, String sku, String name, String nameKm, String unitSymbol, Pack pack,
                             BigDecimal onHand, BigDecimal reorderLevel, BigDecimal soldPerDay, int leadTimeDays,
                             BigDecimal suggestedQty, BigDecimal lastCost, BigDecimal suggestedValue) {
    }

    public record ReorderReport(int coverDays, int salesDays, BigDecimal suggestedValue, List<ReorderRow> rows) {
    }

    public record ExpiryRow(Long batchId, Long productId, String name, String nameKm, String unitSymbol, String warehouse,
                            String batchNo, LocalDate expiryDate, int daysLeft, BigDecimal qty, BigDecimal value) {
    }

    public record ExpiryReport(int days,
                               @Schema(description = "The business's three near-expiry windows (FR-7.10), to pick from")
                               List<Integer> windows,
                               long expired, long expiring, BigDecimal expiredValue, BigDecimal expiringValue,
                               List<ExpiryRow> rows) {
    }

    public record CountRow(Long countId, String number, LocalDate postedOn, String warehouse, Long productId, String name,
                           String nameKm, String unitSymbol, BigDecimal systemQty, BigDecimal countedQty,
                           BigDecimal difference, BigDecimal value) {
    }

    public record CountReport(LocalDate from, LocalDate to, long counts, BigDecimal gainValue, BigDecimal lossValue,
                              List<CountRow> rows) {
    }

    /** Products with their base unit, biggest packaging unit, and stock in the visible warehouses. */
    public record LossRow(String reason, Long productId, String name, String nameKm, String unitSymbol, BigDecimal qty,
                          BigDecimal value) {
    }

    public record LossReason(String reason, long products, BigDecimal value) {
    }

    public record LossReport(LocalDate from, LocalDate to, BigDecimal value, List<LossReason> reasons, List<LossRow> rows) {
    }

    public record SlowRow(Long productId, String sku, String name, String nameKm, String category, String unitSymbol, Pack pack,
                          BigDecimal qty, BigDecimal value, LocalDate lastSold) {
    }

    public record SlowReport(int days, long products, BigDecimal value, List<SlowRow> rows) {
    }

    private static final String ON_HAND = """
            select p.id, p.sku, p.name_en, p.name_km, p.avg_cost, %s as reorder_level, p.lead_time_days, c.name_en as category,
                   u.symbol, pk.symbol as pack_symbol, pk.factor as pack_factor,
                   coalesce((select sum(l.qty_on_hand) from stock_levels l
                              where l.product_id = p.id and l.warehouse_id in (:warehouses)), 0) as qty
              from products p
              join units u on u.id = p.base_unit_id
              left join categories c on c.id = p.category_id
              left join lateral (select pu_u.symbol, pu.factor from product_units pu join units pu_u on pu_u.id = pu.unit_id
                                  where pu.product_id = p.id and pu.factor > 1 order by pu.factor desc limit 1) pk on true
             where p.business_id = :business and p.is_active
            """.formatted(ReorderLevels.sql("b.id in (:branches)"));

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchScope scope;

    /** Stock on hand and its value; stock in transit is its own line (it is in no warehouse). */
    @Transactional(readOnly = true)
    public OnHandReport onHand(Long categoryId) {
        ReportScope.requireStock();
        boolean cost = ReportScope.cost();
        MapSqlParameterSource p = params().addValue("category", categoryId, java.sql.Types.BIGINT);
        List<OnHandRow> rows = jdbc.query("""
                select * from (%s and (cast(:category as bigint) is null or p.category_id = :category)) s
                 where s.qty <> 0 order by s.name_en
                """.formatted(ON_HAND), p, (rs, n) -> {
            BigDecimal qty = rs.getBigDecimal("qty");
            BigDecimal avg = rs.getBigDecimal("avg_cost");
            return new OnHandRow(rs.getLong("id"), rs.getString("sku"), rs.getString("name_en"), rs.getString("name_km"),
                    rs.getString("category"), rs.getString("symbol"), pack(rs.getString("pack_symbol"), rs.getBigDecimal("pack_factor")),
                    qty, cost ? avg : null, cost ? money(qty.multiply(avg)) : null);
        });
        var transit = jdbc.queryForMap("""
                select count(distinct t.id) as transfers, coalesce(sum(i.base_qty * coalesce(i.unit_cost, 0)), 0) as value
                  from stock_transfers t join stock_transfer_items i on i.transfer_id = t.id
                 where t.business_id = :business and t.status = 'IN_TRANSIT'
                   and (t.from_warehouse_id in (:warehouses) or t.to_warehouse_id in (:warehouses))
                """, p);
        BigDecimal value = cost ? rows.stream().map(OnHandRow::value).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        return new OnHandReport(value, rows.size(), cost ? money((BigDecimal) transit.get("value")) : null,
                ((Number) transit.get("transfers")).longValue(), rows);
    }

    /**
     * Low stock with a suggested order: enough for the lead time plus 14 days of average sales (last 30 days), on top
     * of the reorder level kept as safety stock, minus what is on hand; at least back up to twice the reorder level.
     */
    @Transactional(readOnly = true)
    public ReorderReport reorder() {
        ReportScope.requireStock();
        boolean cost = ReportScope.cost();
        LocalDate today = Dates.today();
        MapSqlParameterSource p = params()
                .addValue("from", today.minusDays(SALES_DAYS - 1L))
                .addValue("to", today)
                .addValue("cashier", null, java.sql.Types.BIGINT);
        List<ReorderRow> rows = jdbc.query("""
                select s.*, coalesce(sold.qty, 0) as sold from (%s) s
                  left join (select product_id, sum(base_qty) as qty from (%s) l group by product_id) sold on sold.product_id = s.id
                 where s.reorder_level > 0 and s.qty <= s.reorder_level
                 order by s.qty / nullif(s.reorder_level, 0), s.name_en
                """.formatted(ON_HAND, ReportService.LINES), p, (rs, n) -> {
            BigDecimal onHand = rs.getBigDecimal("qty");
            BigDecimal level = rs.getBigDecimal("reorder_level");
            int lead = rs.getInt("lead_time_days");
            BigDecimal perDay = rs.getBigDecimal("sold").divide(BigDecimal.valueOf(SALES_DAYS), 3, RoundingMode.HALF_UP);
            BigDecimal byDemand = perDay.multiply(BigDecimal.valueOf(lead + COVER_DAYS)).add(level).subtract(onHand);
            BigDecimal byLevel = level.multiply(BigDecimal.valueOf(2)).subtract(onHand);
            BigDecimal suggested = byDemand.max(byLevel).max(BigDecimal.ZERO).setScale(0, RoundingMode.CEILING);
            BigDecimal avg = rs.getBigDecimal("avg_cost");
            return new ReorderRow(rs.getLong("id"), rs.getString("sku"), rs.getString("name_en"), rs.getString("name_km"),
                    rs.getString("symbol"), pack(rs.getString("pack_symbol"), rs.getBigDecimal("pack_factor")), onHand, level,
                    perDay, lead, suggested, cost ? avg : null, cost ? money(suggested.multiply(avg)) : null);
        });
        BigDecimal total = cost ? rows.stream().map(ReorderRow::suggestedValue).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        return new ReorderReport(COVER_DAYS, SALES_DAYS, total, rows);
    }

    /**
     * Economic order quantity (FR-5.9, PLAIN.md 11.9): the order size that keeps ordering plus holding costs lowest,
     * √(2 × D × S / H) with D the yearly demand, S what one order costs and H what holding one unit costs a year (a
     * share of its average cost). Products sold in the last year with a cost, the biggest yearly value first.
     */
    @Transactional(readOnly = true)
    public EoqReport eoq(BigDecimal orderCost, BigDecimal holdingPercent) {
        ReportScope.requireMoney();
        LocalDate today = Dates.today();
        MapSqlParameterSource p = params()
                .addValue("from", today.minusDays(364))
                .addValue("to", today)
                .addValue("cashier", null, java.sql.Types.BIGINT);
        java.sql.Date first = jdbc.queryForObject("select min(day) from (%s) l".formatted(ReportService.LINES), p, java.sql.Date.class);
        int days = first == null ? 365
                : (int) Math.max(30, Math.min(365, java.time.temporal.ChronoUnit.DAYS.between(first.toLocalDate(), today) + 1));
        double s = orderCost.doubleValue();
        double share = holdingPercent.doubleValue() / 100;
        List<EoqRow> rows = jdbc.query("""
                select s.*, sold.qty as sold from (%s) s
                  join (select product_id, sum(base_qty) as qty from (%s) l group by product_id having sum(base_qty) > 0) sold
                    on sold.product_id = s.id
                 where s.avg_cost > 0
                 order by sold.qty * s.avg_cost desc, s.name_en
                """.formatted(ON_HAND, ReportService.LINES), p, (rs, n) -> {
            BigDecimal cost = rs.getBigDecimal("avg_cost");
            double d = rs.getBigDecimal("sold").doubleValue() * 365 / days;
            double h = cost.doubleValue() * share;
            double q = Math.max(1, Math.ceil(Math.sqrt(2 * d * s / h)));
            double perYear = d / q;
            return new EoqRow(rs.getLong("id"), rs.getString("sku"), rs.getString("name_en"), rs.getString("name_km"),
                    rs.getString("symbol"), pack(rs.getString("pack_symbol"), rs.getBigDecimal("pack_factor")),
                    BigDecimal.valueOf(d).setScale(1, RoundingMode.HALF_UP), cost.setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(q).setScale(0, RoundingMode.UNNECESSARY), BigDecimal.valueOf(perYear).setScale(1, RoundingMode.HALF_UP),
                    (int) Math.round(365 / perYear), rs.getBigDecimal("qty"));
        });
        return new EoqReport(days, orderCost, holdingPercent, rows);
    }

    /** Batches in stock that expire within N days, and expired batches still in stock (soonest first). */
    @Transactional(readOnly = true)
    public ExpiryReport expiry(int days, List<Integer> windows) {
        ReportScope.requireStock();
        boolean cost = ReportScope.cost();
        LocalDate today = Dates.today();
        MapSqlParameterSource p = params().addValue("until", today.plusDays(days)).addValue("today", today);
        List<ExpiryRow> rows = jdbc.query("""
                select b.id, b.product_id, p.name_en, p.name_km, u.symbol, w.name_en as warehouse, br.name_en as branch,
                       b.batch_no, b.expiry_date, b.qty_on_hand, b.unit_cost
                  from stock_batches b join products p on p.id = b.product_id join units u on u.id = p.base_unit_id
                  join warehouses w on w.id = b.warehouse_id join branches br on br.id = w.branch_id
                 where b.business_id = :business and b.qty_on_hand > 0 and b.expiry_date is not null
                   and b.expiry_date <= :until and b.warehouse_id in (:warehouses)
                 order by b.expiry_date, p.name_en
                """, p, (rs, n) -> {
            LocalDate expiry = rs.getDate("expiry_date").toLocalDate();
            BigDecimal qty = rs.getBigDecimal("qty_on_hand");
            return new ExpiryRow(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name_en"), rs.getString("name_km"),
                    rs.getString("symbol"), rs.getString("branch") + " · " + rs.getString("warehouse"), rs.getString("batch_no"),
                    expiry, (int) java.time.temporal.ChronoUnit.DAYS.between(today, expiry), qty,
                    cost ? money(qty.multiply(rs.getBigDecimal("unit_cost"))) : null);
        });
        List<ExpiryRow> expired = rows.stream().filter(r -> r.daysLeft() < 0).toList();
        BigDecimal expiredValue = cost ? expired.stream().map(ExpiryRow::value).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal allValue = cost ? rows.stream().map(ExpiryRow::value).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        return new ExpiryReport(days, windows, expired.size(), rows.size() - expired.size(), expiredValue,
                cost ? allValue.subtract(expiredValue) : null, rows);
    }

    /** Products whose counted quantity differed from the system in counts posted in the period. */
    @Transactional(readOnly = true)
    public CountReport countDifferences(LocalDate from, LocalDate to) {
        ReportScope.requireStock();
        boolean cost = ReportScope.cost();
        MapSqlParameterSource p = params().addValue("from", from).addValue("to", to);
        List<CountRow> rows = jdbc.query("""
                select c.id, c.number, (c.posted_at at time zone 'Asia/Phnom_Penh')::date as posted_on, w.name_en as warehouse,
                       i.product_id, p.name_en, p.name_km, u.symbol, i.system_qty, i.counted_qty, i.unit_cost
                  from stock_counts c join stock_count_items i on i.count_id = c.id
                  join products p on p.id = i.product_id join units u on u.id = p.base_unit_id
                  join warehouses w on w.id = i.warehouse_id
                 where c.business_id = :business and c.status = 'POSTED' and c.branch_id in (:branches)
                   and (c.posted_at at time zone 'Asia/Phnom_Penh')::date between :from and :to
                   and i.counted_qty is not null and i.counted_qty <> i.system_qty
                 order by c.posted_at desc, p.name_en
                """, p, (rs, n) -> {
            BigDecimal diff = rs.getBigDecimal("counted_qty").subtract(rs.getBigDecimal("system_qty"));
            return new CountRow(rs.getLong("id"), rs.getString("number"), rs.getDate("posted_on").toLocalDate(),
                    rs.getString("warehouse"), rs.getLong("product_id"), rs.getString("name_en"), rs.getString("name_km"),
                    rs.getString("symbol"), rs.getBigDecimal("system_qty"), rs.getBigDecimal("counted_qty"), diff,
                    cost ? money(diff.multiply(rs.getBigDecimal("unit_cost"))) : null);
        });
        long counts = rows.stream().map(CountRow::countId).distinct().count();
        BigDecimal gain = cost ? rows.stream().map(CountRow::value).filter(v -> v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal loss = cost ? rows.stream().map(CountRow::value).filter(v -> v.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        return new CountReport(from, to, counts, gain, loss, rows);
    }

    /**
     * Stock lost in the period, at its cost then: adjustments that took stock out (by their reason), counts that found
     * less than the system had, and damaged goods customers brought back.
     */
    @Transactional(readOnly = true)
    public LossReport losses(LocalDate from, LocalDate to) {
        ReportScope.requireStock();
        boolean cost = ReportScope.cost();
        MapSqlParameterSource p = params().addValue("from", from).addValue("to", to);
        List<LossRow> rows = jdbc.query("""
                select l.reason, l.product_id, p.name_en, p.name_km, u.symbol, sum(l.qty) as qty, sum(l.value) as value
                  from (select a.reason, i.product_id, -i.qty_change as qty, -i.qty_change * i.unit_cost as value
                          from stock_adjustments a join stock_adjustment_items i on i.adjustment_id = a.id
                         where a.business_id = :business and a.status = 'POSTED' and a.kind = 'ADJUSTMENT' and i.qty_change < 0
                           and a.branch_id in (:branches) and (a.posted_at at time zone 'Asia/Phnom_Penh')::date between :from and :to
                        union all
                        select 'COUNT', i.product_id, i.system_qty - i.counted_qty, (i.system_qty - i.counted_qty) * i.unit_cost
                          from stock_counts c join stock_count_items i on i.count_id = c.id
                         where c.business_id = :business and c.status = 'POSTED' and i.counted_qty < i.system_qty
                           and c.branch_id in (:branches) and (c.posted_at at time zone 'Asia/Phnom_Penh')::date between :from and :to
                        union all
                        select 'DAMAGED_RETURN', ri.product_id, ri.base_qty, ri.base_qty * ri.cost_at_sale
                          from sales_returns sr join sales_return_items ri on ri.return_id = sr.id
                         where sr.business_id = :business and sr.status = 'POSTED' and ri.condition = 'DAMAGED'
                           and sr.branch_id in (:branches) and (sr.returned_at at time zone 'Asia/Phnom_Penh')::date between :from and :to) l
                  join products p on p.id = l.product_id join units u on u.id = p.base_unit_id
                 group by l.reason, l.product_id, p.name_en, p.name_km, u.symbol
                 order by sum(l.value) desc, p.name_en
                """, p, (rs, n) -> new LossRow(rs.getString("reason"), rs.getLong("product_id"), rs.getString("name_en"),
                rs.getString("name_km"), rs.getString("symbol"), rs.getBigDecimal("qty"), cost ? money(rs.getBigDecimal("value")) : null));
        Map<String, List<LossRow>> byReason = new LinkedHashMap<>();
        rows.forEach(r -> byReason.computeIfAbsent(r.reason(), k -> new ArrayList<>()).add(r));
        List<LossReason> reasons = byReason.entrySet().stream()
                .map(e -> new LossReason(e.getKey(), e.getValue().size(),
                        cost ? e.getValue().stream().map(LossRow::value).reduce(BigDecimal.ZERO, BigDecimal::add) : null))
                .toList();
        BigDecimal value = cost ? rows.stream().map(LossRow::value).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        return new LossReport(from, to, value, reasons, rows);
    }

    /** Products in stock that have not sold for `days` days (or never): money sitting on the shelf. */
    @Transactional(readOnly = true)
    public SlowReport slowMoving(int days) {
        ReportScope.requireStock();
        boolean cost = ReportScope.cost();
        MapSqlParameterSource p = params()
                .addValue("since", Dates.today().minusDays(days));
        List<SlowRow> rows = jdbc.query("""
                select s.*, last.sold as last_sold from (%s) s
                  left join lateral (
                    select max(d) as sold from (
                      select (r.sold_at at time zone 'Asia/Phnom_Penh')::date as d from sale_items si
                        join sales_receipts r on r.id = si.sales_receipt_id
                       where si.product_id = s.id and r.status = 'POSTED' and r.branch_id in (:branches)
                      union all
                      select (d.delivered_at at time zone 'Asia/Phnom_Penh')::date from sale_items si
                        join deliveries d on d.id = si.delivery_id
                       where si.product_id = s.id and d.status = 'POSTED' and d.branch_id in (:branches)) x) last on true
                 where s.qty > 0 and (last.sold is null or last.sold < :since)
                 order by last.sold nulls first, s.name_en
                """.formatted(ON_HAND), p, (rs, n) -> {
            BigDecimal qty = rs.getBigDecimal("qty");
            java.sql.Date last = rs.getDate("last_sold");
            return new SlowRow(rs.getLong("id"), rs.getString("sku"), rs.getString("name_en"), rs.getString("name_km"),
                    rs.getString("category"), rs.getString("symbol"), pack(rs.getString("pack_symbol"), rs.getBigDecimal("pack_factor")),
                    qty, cost ? money(qty.multiply(rs.getBigDecimal("avg_cost"))) : null, last == null ? null : last.toLocalDate());
        });
        BigDecimal value = cost ? rows.stream().map(SlowRow::value).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        return new SlowReport(days, rows.size(), value, rows);
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("warehouses", scope.warehouseIds())
                .addValue("branches", scope.branchIds());
    }

    private static Pack pack(String symbol, BigDecimal factor) {
        return symbol == null ? null : new Pack(symbol, factor);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
