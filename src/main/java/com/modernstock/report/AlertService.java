package com.modernstock.report;

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
import com.modernstock.catalog.ReorderLevels;
import com.modernstock.common.Dates;
import lombok.RequiredArgsConstructor;

/**
 * In-app alerts (FR-5.1, FR-5.6): what needs attention now, for the member's role, in the branch selected in the top
 * bar. Stock alerts for whoever sees stock; approvals, transfers to receive and open counts for whoever does them;
 * overdue invoices and bills for Owners and Managers. Only kinds with something in them are returned.
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    public enum Kind {
        PAYMENT_DUE, EXPIRED, OVERDUE_BILLS, TRIAL_ENDING, LOW_STOCK, EXPIRING_30, OVERDUE_INVOICES, APPROVALS, TO_RECEIVE, COUNTS_OPEN, COUNTS_TO_APPROVE,
        EXPIRING_60, EXPIRING_90
    }

    public enum Level { DANGER, WARN, INFO }

    public record Alert(Kind kind, Level level, long count) {
    }

    public record Alerts(long total, List<Alert> alerts) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchScope scope;
    private final DashboardService dashboard;

    @Transactional(readOnly = true)
    public Alerts alerts() {
        List<Alert> list = new ArrayList<>();
        LocalDate today = Dates.today();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("warehouses", scope.warehouseIds())
                .addValue("today", today);
        if (ReportScope.stock()) {
            Map<String, Object> expiry = jdbc.queryForMap("""
                    select count(*) filter (where expiry_date < :today) as expired,
                           count(*) filter (where expiry_date between :today and cast(:today as date) + 30) as d30,
                           count(*) filter (where expiry_date between cast(:today as date) + 31 and cast(:today as date) + 60) as d60,
                           count(*) filter (where expiry_date between cast(:today as date) + 61 and cast(:today as date) + 90) as d90
                      from stock_batches
                     where business_id = :business and qty_on_hand > 0 and expiry_date is not null
                       and warehouse_id in (:warehouses)
                    """, p);
            Long low = jdbc.queryForObject("""
                    select count(*) from (select %s as level,
                                                 coalesce((select sum(l.qty_on_hand) from stock_levels l
                                                            where l.product_id = p.id and l.warehouse_id in (:warehouses)), 0) as qty
                                            from products p where p.business_id = :business and p.is_active) s
                     where s.level > 0 and s.qty <= s.level
                    """.formatted(ReorderLevels.sql("b.id in (:branches)")), p.addValue("branches", scope.branchIds()), Long.class);
            add(list, Kind.EXPIRED, Level.DANGER, expiry.get("expired"));
            add(list, Kind.LOW_STOCK, Level.WARN, low);
            add(list, Kind.EXPIRING_30, Level.WARN, expiry.get("d30"));
            add(list, Kind.EXPIRING_60, Level.INFO, expiry.get("d60"));
            add(list, Kind.EXPIRING_90, Level.INFO, expiry.get("d90"));
        }
        DashboardService.Waiting waiting = dashboard.waiting();
        if (waiting != null) {
            add(list, Kind.APPROVALS, Level.INFO, waiting.approvals());
            add(list, Kind.TO_RECEIVE, Level.INFO, waiting.transfersToReceive());
            add(list, Kind.COUNTS_OPEN, Level.INFO, waiting.countsInProgress());
            add(list, Kind.COUNTS_TO_APPROVE, Level.INFO, waiting.countsToApprove());
        }
        if (ReportScope.money()) {
            Map<String, Object> overdue = jdbc.queryForMap("""
                    select (select count(*) from invoices where business_id = :business and status in ('UNPAID', 'PARTLY_PAID')
                              and due_date < :today) as invoices,
                           (select count(*) from bills where business_id = :business and status in ('UNPAID', 'PARTLY_PAID')
                              and due_date < :today) as bills
                    """, p);
            add(list, Kind.OVERDUE_BILLS, Level.DANGER, overdue.get("bills"));
            add(list, Kind.OVERDUE_INVOICES, Level.WARN, overdue.get("invoices"));
        }
        if (BusinessContext.has("billing:manage")) {
            // The Owner: a billing invoice to pay (past due, or after subscribing), or a trial ending without a plan.
            Map<String, Object> billing = jdbc.queryForMap("""
                    select (select count(*) from billing_invoices i where i.business_id = :business and i.status = 'OPEN') as open,
                           (select count(*) from subscriptions s where s.business_id = :business and s.status = 'TRIALING'
                              and not s.subscribed and s.trial_ends_at < now() + interval '7 days') as trial_ending
                    """, p);
            add(list, Kind.PAYMENT_DUE, Level.DANGER, billing.get("open"));
            add(list, Kind.TRIAL_ENDING, Level.WARN, billing.get("trial_ending"));
        }
        list.sort((a, b) -> a.kind().compareTo(b.kind()));
        return new Alerts(list.stream().mapToLong(Alert::count).sum(), list);
    }

    private static void add(List<Alert> list, Kind kind, Level level, Object count) {
        long n = count == null ? 0 : ((Number) count).longValue();
        if (n > 0) {
            list.add(new Alert(kind, level, n));
        }
    }
}
