package com.modernstock.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.billing.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The platform admin area (PLAIN.md M8), for super admins only (SecurityConfig: /admin/**). Businesses with their
 * subscription, and the demo controls that show a whole month of billing in two minutes (FR-8.7).
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    public record BusinessRow(Long id, String nameEn, String nameKm, String status, Instant createdAt, String ownerEmail,
                              long members, long branches, String planCode, String subscriptionStatus, Instant trialEndsAt,
                              Instant currentPeriodEnd, Instant pastDueSince, boolean cancelAtPeriodEnd,
                              BigDecimal openInvoiceAmount) {
    }

    private final JdbcTemplate jdbc;
    private final BillingService billing;

    @Operation(summary = "Businesses with their subscription", description = "Newest first.")
    @GetMapping("/businesses")
    @Transactional(readOnly = true)
    public List<BusinessRow> adminBusinesses() {
        return jdbc.query("""
                select b.id, b.name_en, b.name_km, b.status, b.created_at,
                       (select u.email from business_members m join roles r on r.id = m.role_id join users u on u.id = m.user_id
                         where m.business_id = b.id and r.code = 'OWNER' order by m.joined_at limit 1) as owner_email,
                       (select count(*) from business_members m where m.business_id = b.id and m.status = 'ACTIVE') as members,
                       (select count(*) from branches br where br.business_id = b.id and br.is_active) as branches,
                       p.code as plan_code, s.status as sub_status, s.trial_ends_at, s.current_period_end, s.past_due_since,
                       s.cancel_at_period_end,
                       (select sum(i.amount) from billing_invoices i where i.business_id = b.id and i.status = 'OPEN') as open_amount
                  from businesses b left join subscriptions s on s.business_id = b.id left join plans p on p.id = s.plan_id
                 order by b.created_at desc
                """, (rs, n) -> new BusinessRow(rs.getLong("id"), rs.getString("name_en"), rs.getString("name_km"),
                rs.getString("status"), instant(rs.getTimestamp("created_at")), rs.getString("owner_email"),
                rs.getLong("members"), rs.getLong("branches"), rs.getString("plan_code"), rs.getString("sub_status"),
                instant(rs.getTimestamp("trial_ends_at")), instant(rs.getTimestamp("current_period_end")),
                instant(rs.getTimestamp("past_due_since")), rs.getBoolean("cancel_at_period_end"),
                rs.getBigDecimal("open_amount")));
    }

    @Operation(summary = "Demo control: End trial now",
            description = "The trial ends now: a subscribed business gets its first invoice (past due until paid), else it moves to Free.")
    @PostMapping("/businesses/{id}/end-trial")
    public void endTrialNow(@PathVariable Long id) {
        billing.endTrialNow(id);
    }

    @Operation(summary = "Demo control: Run renewal now",
            description = "Active: the period ends now (renewal invoice, past due until paid, or Free after a cancel). "
                    + "Past due: the 7 days are over (expires to Free).")
    @PostMapping("/businesses/{id}/run-renewal")
    public void runRenewalNow(@PathVariable Long id) {
        billing.runRenewalNow(id);
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
