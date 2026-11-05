package com.modernstock.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The platform admin area, part three (FR-8.8 to FR-8.10), for super admins only (SecurityConfig: /admin/**): platform
 * settings with the announcement banner, and the numbers of the platform.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminPlatformController {

    public record PlatformSettingsBody(
            @Schema(description = "Days of Pro trial a new business gets") @Min(1) @Max(365) int trialDays,
            @Schema(description = "Businesses one account may create (archived ones don't count)") @Min(1) @Max(100) int maxBusinessesPerAccount,
            @Schema(description = "Days an invitation link stays valid") @Min(1) @Max(60) int invitationExpiryDays,
            @Schema(description = "Banner over the web app for everyone; empty = no banner") @Size(max = 255) String announcementEn,
            @Schema(description = "The same in Khmer; empty = the English one") @Size(max = 255) String announcementKm,
            @Schema(allowableValues = {"INFO", "WARN"}) @Pattern(regexp = "INFO|WARN") String announcementLevel) {
    }

    public record WeekPoint(@Schema(description = "Monday of the week, Phnom Penh time") LocalDate weekStart,
                            long accounts, long businesses) {
    }

    public record PlatformStats(long accounts, long verifiedAccounts,
                                @Schema(description = "Accounts created in the last 7 days") long newAccounts,
                                @Schema(description = "SETTING_UP, ACTIVE, SUSPENDED, ARCHIVED") Map<String, Long> businessesByStatus,
                                @Schema(description = "Active businesses where someone signed in during the last 7 days") long businessesInUse,
                                @Schema(description = "Businesses not archived, by plan; TRIAL while on the trial") Map<String, Long> businessesByPlan,
                                @Schema(description = "Paid plans and add-ons per month (a yearly plan counts as a twelfth)") BigDecimal monthlyRevenue,
                                @Schema(description = "The last 12 weeks, oldest first") List<WeekPoint> weeks) {
    }

    private final JdbcTemplate jdbc;
    private final PlatformSettings settings;

    @Operation(summary = "Platform settings", description = "FR-8.8, FR-8.10: trial length, businesses per account, invitation expiry, announcement.")
    @GetMapping("/settings")
    public PlatformSettingsBody platformSettings() {
        return new PlatformSettingsBody(
                settings.getInt(PlatformSettings.TRIAL_DAYS, PlatformSettings.DEFAULT_TRIAL_DAYS),
                settings.getInt(PlatformSettings.MAX_BUSINESSES, PlatformSettings.DEFAULT_MAX_BUSINESSES),
                settings.getInt(PlatformSettings.INVITATION_DAYS, PlatformSettings.DEFAULT_INVITATION_DAYS),
                settings.getString(PlatformSettings.ANNOUNCEMENT_EN, ""),
                settings.getString(PlatformSettings.ANNOUNCEMENT_KM, ""),
                settings.getString(PlatformSettings.ANNOUNCEMENT_LEVEL, "INFO"));
    }

    @Operation(summary = "Change the platform settings",
            description = "New values apply from now on: a new trial, a new business, a new invitation. The banner shows within minutes.")
    @PutMapping("/settings")
    @Transactional
    public PlatformSettingsBody updatePlatformSettings(@Valid @RequestBody PlatformSettingsBody req) {
        settings.set(PlatformSettings.TRIAL_DAYS, String.valueOf(req.trialDays()));
        settings.set(PlatformSettings.MAX_BUSINESSES, String.valueOf(req.maxBusinessesPerAccount()));
        settings.set(PlatformSettings.INVITATION_DAYS, String.valueOf(req.invitationExpiryDays()));
        settings.set(PlatformSettings.ANNOUNCEMENT_EN, trim(req.announcementEn()));
        settings.set(PlatformSettings.ANNOUNCEMENT_KM, trim(req.announcementKm()));
        settings.set(PlatformSettings.ANNOUNCEMENT_LEVEL, req.announcementLevel() == null ? "INFO" : req.announcementLevel());
        return platformSettings();
    }

    @Operation(summary = "Platform statistics", description = "FR-8.9: accounts, businesses by status and plan, sign-ups per week. Super admins are left out.")
    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public PlatformStats platformStats() {
        Map<String, Object> accounts = jdbc.queryForMap("""
                select count(*) as total, count(email_verified_at) as verified,
                       count(*) filter (where created_at > now() - interval '7 days') as fresh
                  from users where not is_super_admin and managed_by_business_id is null
                """);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : List.of("SETTING_UP", "ACTIVE", "SUSPENDED", "ARCHIVED")) {
            byStatus.put(s, 0L);
        }
        jdbc.query("select status, count(*) as n from businesses group by status",
                rs -> {
                    byStatus.put(rs.getString("status"), rs.getLong("n"));
                });
        Long inUse = jdbc.queryForObject("""
                select count(*) from businesses b where b.status = 'ACTIVE'
                   and exists (select 1 from business_members m join users u on u.id = m.user_id
                                where m.business_id = b.id and m.status = 'ACTIVE' and u.last_login_at > now() - interval '7 days')
                """, Long.class);
        Map<String, Long> byPlan = new LinkedHashMap<>();
        jdbc.query("""
                select case when s.status = 'TRIALING' then 'TRIAL' else p.code end as plan, count(*) as n
                  from businesses b join subscriptions s on s.business_id = b.id join plans p on p.id = s.plan_id
                 where b.status <> 'ARCHIVED'
                 group by 1 order by min(case when s.status = 'TRIALING' then -1 else p.sort_order end)
                """, rs -> {
            byPlan.put(rs.getString("plan"), rs.getLong("n"));
        });
        BigDecimal revenue = jdbc.queryForObject("""
                select coalesce((select sum(case when s.billing_cycle = 'YEARLY' then coalesce(p.price_yearly, p.price_monthly * 12) / 12
                                                 else p.price_monthly end)
                                   from subscriptions s join plans p on p.id = s.plan_id join businesses b on b.id = s.business_id
                                  where s.status = 'ACTIVE' and b.status <> 'ARCHIVED'), 0)
                     + coalesce((select sum(a.price_monthly * sa.quantity)
                                   from subscription_addons sa join addons a on a.id = sa.addon_id
                                   join subscriptions s on s.business_id = sa.business_id join businesses b on b.id = sa.business_id
                                  where sa.status = 'ACTIVE' and s.status = 'ACTIVE' and b.status <> 'ARCHIVED'), 0)
                """, BigDecimal.class);
        List<WeekPoint> weeks = jdbc.query("""
                with w as (select cast(generate_series(cast(date_trunc('week', now() at time zone 'Asia/Phnom_Penh') as date) - 77,
                                                       cast(date_trunc('week', now() at time zone 'Asia/Phnom_Penh') as date),
                                                       interval '7 days') as date) as start)
                select w.start,
                       (select count(*) from users u where not u.is_super_admin and u.managed_by_business_id is null
                          and (u.created_at at time zone 'Asia/Phnom_Penh')::date >= w.start
                          and (u.created_at at time zone 'Asia/Phnom_Penh')::date < w.start + 7) as accounts,
                       (select count(*) from businesses b where (b.created_at at time zone 'Asia/Phnom_Penh')::date >= w.start
                          and (b.created_at at time zone 'Asia/Phnom_Penh')::date < w.start + 7) as businesses
                  from w order by w.start
                """, (rs, n) -> new WeekPoint(rs.getDate("start").toLocalDate(), rs.getLong("accounts"), rs.getLong("businesses")));
        return new PlatformStats(((Number) accounts.get("total")).longValue(), ((Number) accounts.get("verified")).longValue(),
                ((Number) accounts.get("fresh")).longValue(), byStatus, inUse == null ? 0 : inUse, byPlan,
                revenue == null ? BigDecimal.ZERO : revenue.setScale(2, java.math.RoundingMode.HALF_UP), weeks);
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }
}
