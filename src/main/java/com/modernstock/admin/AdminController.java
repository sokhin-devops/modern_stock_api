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
import com.modernstock.billing.CouponService;
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
                              BigDecimal openInvoiceAmount, String billingCycle,
                              @io.swagger.v3.oas.annotations.media.Schema(description = "Active add-ons, e.g. \"Extra branch ×2\"") String addons) {
    }

    private final JdbcTemplate jdbc;
    private final BillingService billing;
    private final CouponService coupons;
    private final com.modernstock.activity.ActivityService activity;

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
                       (select sum(i.amount) from billing_invoices i where i.business_id = b.id and i.status = 'OPEN') as open_amount,
                       s.billing_cycle,
                       (select string_agg(a.name || case when sa.quantity > 1 then ' ×' || sa.quantity else '' end, ', ' order by a.sort_order)
                          from subscription_addons sa join addons a on a.id = sa.addon_id
                         where sa.business_id = b.id and sa.status = 'ACTIVE') as addons
                  from businesses b left join subscriptions s on s.business_id = b.id left join plans p on p.id = s.plan_id
                 order by b.created_at desc
                """, (rs, n) -> new BusinessRow(rs.getLong("id"), rs.getString("name_en"), rs.getString("name_km"),
                rs.getString("status"), instant(rs.getTimestamp("created_at")), rs.getString("owner_email"),
                rs.getLong("members"), rs.getLong("branches"), rs.getString("plan_code"), rs.getString("sub_status"),
                instant(rs.getTimestamp("trial_ends_at")), instant(rs.getTimestamp("current_period_end")),
                instant(rs.getTimestamp("past_due_since")), rs.getBoolean("cancel_at_period_end"),
                rs.getBigDecimal("open_amount"), rs.getString("billing_cycle"), rs.getString("addons")));
    }

    @Operation(summary = "Suspend a business", description = "FR-8.2: its members can't open it until it is reactivated; nothing is deleted.")
    @PostMapping("/businesses/{id}/suspend")
    @Transactional
    public void suspendBusiness(@PathVariable Long id) {
        if (jdbc.update("update businesses set status = 'SUSPENDED', updated_at = now() where id = ? and status = 'ACTIVE'", id) == 0) {
            throw com.modernstock.common.ApiException.conflict("ADMIN_BUSINESS_STATUS");
        }
    }

    @Operation(summary = "Reactivate a suspended business")
    @PostMapping("/businesses/{id}/reactivate")
    @Transactional
    public void reactivateBusiness(@PathVariable Long id) {
        if (jdbc.update("update businesses set status = 'ACTIVE', updated_at = now() where id = ? and status = 'SUSPENDED'", id) == 0) {
            throw com.modernstock.common.ApiException.conflict("ADMIN_BUSINESS_STATUS");
        }
    }

    public record FreeMonths(@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(24) int months,
                             @io.swagger.v3.oas.annotations.media.Schema(description = "For a business on Free: the plan to give (PRO by default)")
                             String planCode,
                             @jakarta.validation.constraints.Size(max = 120) String note) {
    }

    @Operation(summary = "Give free months",
            description = "FR-10.16: a trial or a paid period runs longer; a business on Free gets the plan for that long, then back to "
                    + "Free unless the Owner keeps it. Refused while past due. In the business's activity log.")
    @PostMapping("/businesses/{id}/free-months")
    @Transactional
    public void giveFreeMonths(@PathVariable Long id, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody FreeMonths req) {
        billing.giveFreeMonths(id, req.months(), req.planCode());
        activity.recordAs(id, com.modernstock.common.CurrentUser.id(), "FREE_MONTHS_GIVEN", "SUBSCRIPTION", null,
                req.months() + (req.note() == null || req.note().isBlank() ? "" : " · " + req.note().strip()));
    }

    // ---------------------------------------------------------------- coupons (FR-10.16)

    @Operation(summary = "Coupons", description = "Newest first, with how many businesses entered each.")
    @GetMapping("/coupons")
    public List<CouponService.CouponRow> coupons() {
        return coupons.list();
    }

    @PostMapping("/coupons")
    public List<CouponService.CouponRow> createCoupon(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody CouponService.CouponRequest req) {
        coupons.create(req);
        return coupons.list();
    }

    @org.springframework.web.bind.annotation.PutMapping("/coupons/{id}")
    public List<CouponService.CouponRow> updateCoupon(@PathVariable Long id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody CouponService.CouponRequest req) {
        coupons.update(id, req);
        return coupons.list();
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
