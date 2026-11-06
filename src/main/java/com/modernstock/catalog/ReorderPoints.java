package com.modernstock.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.common.Dates;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reorder points from sales history (PLAIN.md 11.9), recalculated every morning before the 07:00 summary, per branch and
 * product:
 * <ul>
 * <li>ADS = quantity sold in the last 30 days / 30 (sales receipts and invoices of that branch);</li>
 * <li>safety stock = (max daily sales × max lead time) − (ADS × average lead time), lead times from the product's goods
 * receipts in that branch over the last year (order date to received), or its set lead time with fewer than two;</li>
 * <li>reorder point = ADS × lead time + safety stock.</li>
 * </ul>
 * Only products sold on at least 7 of the 30 days get a point; the others keep the manual level (ReorderLevels).
 * Native SQL is not filtered by @TenantId, so every query has business_id by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReorderPoints {

    static final int SALES_DAYS = 30;
    static final int MIN_SALE_DAYS = 7;
    static final int MIN_RECEIPTS = 2;

    private final NamedParameterJdbcTemplate jdbc;

    /** A new install (or the first start after this table came) does not wait for the morning. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void firstRun() {
        Long rows = jdbc.queryForObject("select count(*) from reorder_points", new MapSqlParameterSource(), Long.class);
        if (rows != null && rows == 0) {
            recalculateAll();
        }
    }

    /** Every active business, before the daily summary goes out at 07:00. */
    @Scheduled(cron = "0 50 6 * * *", zone = "Asia/Phnom_Penh")
    public void recalculateAll() {
        List<Long> businesses = jdbc.queryForList("select id from businesses where status = 'ACTIVE' order by id",
                new MapSqlParameterSource(), Long.class);
        int done = 0;
        for (Long id : businesses) {
            try {
                recalculate(id);
                done++;
            } catch (RuntimeException e) {
                log.warn("Reorder points for business {} failed: {}", id, e.getMessage());
            }
        }
        log.info("Reorder points recalculated for {} of {} businesses", done, businesses.size());
    }

    /** One business's points, replaced in one transaction. Returns how many products have a point now. */
    @Transactional
    public int recalculate(Long businessId) {
        LocalDate today = Dates.today();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("business", businessId)
                .addValue("from", today.minusDays(SALES_DAYS - 1L))
                .addValue("to", today)
                .addValue("minDays", MIN_SALE_DAYS);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                with sold as (
                    select r.branch_id, si.product_id, (r.sold_at at time zone 'Asia/Phnom_Penh')::date as day, si.base_qty as qty
                      from sale_items si join sales_receipts r on r.id = si.sales_receipt_id
                     where r.business_id = :business and r.status = 'POSTED'
                       and (r.sold_at at time zone 'Asia/Phnom_Penh')::date between :from and :to
                    union all
                    select i.branch_id, si.product_id, i.invoice_date, si.base_qty
                      from sale_items si join invoices i on i.delivery_id = si.delivery_id
                     where i.business_id = :business and i.status <> 'VOID' and i.invoice_date between :from and :to
                ), daily as (
                    select branch_id, product_id, day, sum(qty) as qty from sold group by branch_id, product_id, day
                ), stats as (
                    select branch_id, product_id, sum(qty) as total, max(qty) as max_day, count(*) filter (where qty > 0) as days
                      from daily group by branch_id, product_id
                ), leads as (
                    select g.branch_id, gi.product_id, avg(((g.received_at at time zone 'Asia/Phnom_Penh')::date - po.order_date)) as avg_lead,
                           max(((g.received_at at time zone 'Asia/Phnom_Penh')::date - po.order_date)) as max_lead, count(*) as receipts
                      from goods_receipt_items gi
                      join goods_receipts g on g.id = gi.goods_receipt_id
                      join purchase_orders po on po.id = g.purchase_order_id
                     where g.business_id = :business and g.status = 'POSTED' and g.received_at >= now() - interval '365 days'
                     group by g.branch_id, gi.product_id
                )
                select s.branch_id, s.product_id, s.total, s.max_day, p.lead_time_days, l.avg_lead, l.max_lead,
                       coalesce(l.receipts, 0) as receipts
                  from stats s
                  join products p on p.id = s.product_id and p.business_id = :business and p.is_active
                  left join leads l on l.branch_id = s.branch_id and l.product_id = s.product_id
                 where s.days >= :minDays
                """, p);
        jdbc.update("delete from reorder_points where business_id = :business", p);
        List<MapSqlParameterSource> inserts = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal ads = ((BigDecimal) r.get("total")).divide(BigDecimal.valueOf(SALES_DAYS), 3, RoundingMode.HALF_UP);
            BigDecimal maxDay = (BigDecimal) r.get("max_day");
            int set = ((Number) r.get("lead_time_days")).intValue();
            boolean real = ((Number) r.get("receipts")).longValue() >= MIN_RECEIPTS;
            BigDecimal avgLead = real ? (BigDecimal) r.get("avg_lead") : BigDecimal.valueOf(set);
            BigDecimal maxLead = real ? BigDecimal.valueOf(Math.max(((Number) r.get("max_lead")).intValue(), set)) : BigDecimal.valueOf(set);
            BigDecimal safety = maxDay.multiply(maxLead).subtract(ads.multiply(avgLead)).max(BigDecimal.ZERO)
                    .setScale(0, RoundingMode.CEILING);
            BigDecimal point = ads.multiply(BigDecimal.valueOf(set)).add(safety).setScale(0, RoundingMode.CEILING);
            inserts.add(new MapSqlParameterSource()
                    .addValue("business", businessId)
                    .addValue("branch", r.get("branch_id"))
                    .addValue("product", r.get("product_id"))
                    .addValue("point", point)
                    .addValue("safety", safety)
                    .addValue("ads", ads)
                    .addValue("maxDay", maxDay)
                    .addValue("lead", avgLead.setScale(2, RoundingMode.HALF_UP)));
        }
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into reorder_points (business_id, branch_id, product_id, reorder_point, safety_stock, ads, max_daily, lead_days)
                    values (:business, :branch, :product, :point, :safety, :ads, :maxDay, :lead)
                    """, inserts.toArray(MapSqlParameterSource[]::new));
        }
        return inserts.size();
    }
}
