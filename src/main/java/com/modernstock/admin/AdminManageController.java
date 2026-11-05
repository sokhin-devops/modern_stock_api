package com.modernstock.admin;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.auth.RefreshTokenRepository;
import com.modernstock.billing.BillingPayment;
import com.modernstock.billing.BillingPaymentRepository;
import com.modernstock.billing.BillingReminders;
import com.modernstock.billing.BillingService;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.common.Dates;
import com.modernstock.common.FileStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The platform admin area, part two (PLAIN.md M8, FR-8.1 to FR-8.6), for super admins only (SecurityConfig: /admin/**):
 * accounts (suspend, reactivate, verify by hand), contact messages from the website, plans and add-ons, and bank
 * transfers to approve. Businesses and the demo controls are in AdminController.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminManageController {

    public record AccountRow(Long id, String fullName, String username, String email, String phone, String status,
                             Instant emailVerifiedAt, Instant createdAt, Instant lastLoginAt, long businesses, boolean superAdmin) {
    }

    public record MessageRow(Long id, String name, String phone, String email, String message, String language, String status,
                             Instant createdAt) {
    }

    public record MessageStatus(@NotBlank String status) {
    }

    public record PlanRow(Long id, String code, String name, BigDecimal priceMonthly, BigDecimal priceYearly, boolean publicPlan,
                          @Schema(description = "null = unlimited; a feature: 1 on, 0 off") Map<String, Integer> limits,
                          long subscriptions) {
    }

    public record PlanUpdate(@NotBlank @Size(max = 60) String name, @NotNull @DecimalMin("0") BigDecimal priceMonthly,
                             @DecimalMin("0") BigDecimal priceYearly, boolean publicPlan, @NotNull Map<String, Integer> limits) {
    }

    public record AddonRow(Long id, String code, String name, String nameKm, String limitKey, int quantity, BigDecimal priceMonthly, boolean active,
                           long inUse) {
    }

    public record AddonUpdate(@NotBlank @Size(max = 60) String name, @Size(max = 60) String nameKm, @Min(1) int quantity, @NotNull @DecimalMin("0") BigDecimal priceMonthly,
                              boolean active) {
    }

    public record TransferRow(Long id, Long businessId, String businessName, String invoiceNumber, String description,
                              BigDecimal amount, String bank, String reference, String status, String failureReason,
                              Instant createdAt, Instant completedAt) {
    }

    public record RejectRequest(@Size(max = 255) String reason) {
    }

    private static final Set<String> LIMIT_KEYS = Set.of("branches", "members", "products", "telegram_alerts", "excel_export", "tax_invoice");

    private final JdbcTemplate jdbc;
    private final RefreshTokenRepository refreshTokens;
    private final BillingPaymentRepository payments;
    private final BillingService billing;
    private final BillingReminders reminders;
    private final FileStorage files;

    // ---------------------------------------------------------------- accounts (FR-8.1 to FR-8.3)

    @Operation(summary = "Accounts", description = "Newest first; q matches name, username, email or phone. At most 200.")
    @GetMapping("/accounts")
    @Transactional(readOnly = true)
    public List<AccountRow> accounts(@RequestParam(required = false) String q) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase() + "%";
        return jdbc.query("""
                select u.id, u.full_name, u.username, u.email, u.phone, u.status, u.email_verified_at, u.created_at, u.last_login_at,
                       u.is_super_admin,
                       (select count(*) from business_members m where m.user_id = u.id and m.status <> 'REMOVED') as businesses
                  from users u
                 where lower(u.full_name) like ? or lower(coalesce(u.username, '')) like ? or lower(coalesce(u.email, '')) like ?
                    or coalesce(u.phone, '') like ?
                 order by u.created_at desc limit 200
                """, (rs, n) -> new AccountRow(rs.getLong("id"), rs.getString("full_name"), rs.getString("username"), rs.getString("email"),
                rs.getString("phone"), rs.getString("status"), instant(rs.getTimestamp("email_verified_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_login_at")), rs.getLong("businesses"),
                rs.getBoolean("is_super_admin")), like, like, like, like);
    }

    @Operation(summary = "Suspend an account", description = "The person can't log in and is logged out everywhere.")
    @PostMapping("/accounts/{id}/suspend")
    @Transactional
    public void suspendAccount(@PathVariable Long id) {
        Map<String, Object> u = account(id);
        if (Boolean.TRUE.equals(u.get("is_super_admin"))) {
            throw ApiException.rule("ADMIN_CANT_SUSPEND_ADMIN");
        }
        jdbc.update("update users set status = 'DISABLED', updated_at = now() where id = ?", id);
        refreshTokens.revokeAll(id, Instant.now());
    }

    @Operation(summary = "Reactivate an account", description = "Active again (or still waiting for email verification).")
    @PostMapping("/accounts/{id}/reactivate")
    @Transactional
    public void reactivateAccount(@PathVariable Long id) {
        account(id);
        jdbc.update("""
                update users set status = case when email_verified_at is not null or email is null then 'ACTIVE' else 'UNVERIFIED' end,
                       updated_at = now() where id = ? and status = 'DISABLED'
                """, id);
    }

    @Operation(summary = "Verify an account by hand", description = "FR-8.3: when the verification email landed in spam.")
    @PostMapping("/accounts/{id}/verify")
    @Transactional
    public void verifyAccount(@PathVariable Long id) {
        account(id);
        jdbc.update("""
                update users set email_verified_at = coalesce(email_verified_at, now()),
                       status = case when status = 'UNVERIFIED' then 'ACTIVE' else status end, updated_at = now() where id = ?
                """, id);
    }

    // ---------------------------------------------------------------- contact messages (FR-8.4)

    @Operation(summary = "Contact messages from the website", description = "Newest first; status NEW, READ or DONE.")
    @GetMapping("/messages")
    @Transactional(readOnly = true)
    public List<MessageRow> messages(@RequestParam(required = false) String status) {
        return jdbc.query("""
                select id, name, phone, email, message, language, status, created_at from contact_messages
                 where (cast(? as varchar) is null or status = ?) order by created_at desc limit 300
                """, (rs, n) -> new MessageRow(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("email"),
                rs.getString("message"), rs.getString("language"), rs.getString("status"), instant(rs.getTimestamp("created_at"))),
                status, status);
    }

    @Operation(summary = "Mark a message", description = "NEW, READ or DONE.")
    @PostMapping("/messages/{id}/status")
    @Transactional
    public void markMessage(@PathVariable Long id, @Valid @RequestBody MessageStatus req) {
        if (!Set.of("NEW", "READ", "DONE").contains(req.status())) {
            throw ApiException.badRequest("VALIDATION_FAILED");
        }
        if (jdbc.update("update contact_messages set status = ?, updated_at = now() where id = ?", req.status(), id) == 0) {
            throw ApiException.notFound("message");
        }
    }

    // ---------------------------------------------------------------- plans and add-ons (FR-8.5)

    @Operation(summary = "Plans with their limits", description = "Hidden plans too.")
    @GetMapping("/plans")
    @Transactional(readOnly = true)
    public List<PlanRow> plans() {
        return jdbc.query("""
                select p.*, (select count(*) from subscriptions s where s.plan_id = p.id and s.status in ('ACTIVE', 'PAST_DUE')) as used
                  from plans p order by p.sort_order
                """, (rs, n) -> {
            Map<String, Integer> limits = new LinkedHashMap<>();
            jdbc.query("select limit_key, value from plan_limits where plan_id = ? order by id",
                    (r, i) -> limits.put(r.getString(1), (Integer) r.getObject(2)), rs.getLong("id"));
            return new PlanRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBigDecimal("price_monthly"),
                    rs.getBigDecimal("price_yearly"), rs.getBoolean("is_public"), limits, rs.getLong("used"));
        });
    }

    @Operation(summary = "Change a plan", description = "Prices apply to the next invoices; limits apply now. The Free plan stays free.")
    @PutMapping("/plans/{id}")
    @Transactional
    public List<PlanRow> updatePlan(@PathVariable Long id, @Valid @RequestBody PlanUpdate req) {
        String code = jdbc.query("select code from plans where id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        if (code == null) {
            throw ApiException.notFound("plan");
        }
        if ("FREE".equals(code) && (req.priceMonthly().signum() != 0 || (req.priceYearly() != null && req.priceYearly().signum() != 0))) {
            throw ApiException.rule("ADMIN_FREE_PLAN_PRICE");
        }
        if (!LIMIT_KEYS.containsAll(req.limits().keySet()) || req.limits().values().stream().anyMatch(v -> v != null && v < 0)) {
            throw ApiException.badRequest("VALIDATION_FAILED");
        }
        jdbc.update("update plans set name = ?, price_monthly = ?, price_yearly = ?, is_public = ? where id = ?",
                req.name().trim(), req.priceMonthly(), req.priceYearly(), req.publicPlan(), id);
        req.limits().forEach((key, value) -> jdbc.update("""
                insert into plan_limits (plan_id, limit_key, value) values (?, ?, ?)
                on conflict (plan_id, limit_key) do update set value = excluded.value
                """, id, key, value));
        return plans();
    }

    @Operation(summary = "Add-ons", description = "With how many businesses have each.")
    @GetMapping("/addons")
    @Transactional(readOnly = true)
    public List<AddonRow> addons() {
        return jdbc.query("""
                select a.*, (select count(*) from subscription_addons sa where sa.addon_id = a.id and sa.status = 'ACTIVE') as used
                  from addons a order by a.sort_order
                """, (rs, n) -> new AddonRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("name_km"), rs.getString("limit_key"),
                rs.getInt("quantity"), rs.getBigDecimal("price_monthly"), rs.getBoolean("is_active"), rs.getLong("used")));
    }

    @Operation(summary = "Change an add-on", description = "A price applies to the next invoices; an inactive add-on can't be bought.")
    @PutMapping("/addons/{id}")
    @Transactional
    public List<AddonRow> updateAddon(@PathVariable Long id, @Valid @RequestBody AddonUpdate req) {
        String nameKm = req.nameKm() == null || req.nameKm().isBlank() ? null : req.nameKm().trim();
        if (jdbc.update("update addons set name = ?, name_km = ?, quantity = ?, price_monthly = ?, is_active = ? where id = ?",
                req.name().trim(), nameKm, req.quantity(), req.priceMonthly(), req.active(), id) == 0) {
            throw ApiException.notFound("add-on");
        }
        return addons();
    }

    // ---------------------------------------------------------------- bank transfers (FR-8.6, FR-10.14)

    @Operation(summary = "Bank transfers", description = "PENDING (default) waits for approval; ALL shows the last 200.")
    @GetMapping("/transfers")
    @Transactional(readOnly = true)
    public List<TransferRow> transfers(@RequestParam(defaultValue = "PENDING") String status) {
        boolean all = "ALL".equalsIgnoreCase(status);
        return jdbc.query("""
                select p.id, p.business_id, b.name_en, i.number, i.description, p.amount, p.bank, p.reference, p.status,
                       p.failure_reason, p.created_at, p.completed_at
                  from billing_payments p join billing_invoices i on i.id = p.invoice_id join businesses b on b.id = p.business_id
                 where p.gateway = ? and (? or p.status = 'PENDING')
                 order by p.created_at desc limit 200
                """, (rs, n) -> new TransferRow(rs.getLong("id"), rs.getLong("business_id"), rs.getString("name_en"), rs.getString("number"),
                rs.getString("description"), rs.getBigDecimal("amount"), rs.getString("bank"), rs.getString("reference"),
                rs.getString("status"), rs.getString("failure_reason"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("completed_at"))), BillingService.BANK_TRANSFER, all);
    }

    @Operation(summary = "A transfer's screenshot")
    @GetMapping("/transfers/{id}/proof")
    public ResponseEntity<Resource> transferProof(@PathVariable Long id) {
        BillingPayment p = payments.findById(id).filter(x -> BillingService.BANK_TRANSFER.equals(x.getGateway()))
                .orElseThrow(() -> ApiException.notFound("bank transfer"));
        Resource body = p.getProofPath() == null ? null
                : files.resolve(p.getProofPath()).filter(java.nio.file.Files::isRegularFile)
                .map(path -> (Resource) new org.springframework.core.io.FileSystemResource(path)).orElse(null);
        if (body == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(FileStorage.contentType(p.getProofPath())))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff").body(body);
    }

    @Operation(summary = "Approve a bank transfer", description = "The invoice is paid and what it is for applies.")
    @PostMapping("/transfers/{id}/approve")
    public void approveTransfer(@PathVariable Long id) {
        billing.review(id, true, null, CurrentUser.id());
    }

    @Operation(summary = "Reject a bank transfer", description = "The Owner sees the reason; the invoice stays open.")
    @PostMapping("/transfers/{id}/reject")
    public void rejectTransfer(@PathVariable Long id, @Valid @RequestBody RejectRequest req) {
        billing.review(id, false, req.reason(), CurrentUser.id());
    }

    @Operation(summary = "Demo control: send the billing reminders now", description = "FR-10.15: 7, 3 and 1 days before a renewal "
            + "or a trial end; each is sent once. date (default today) shows what a later day would send. Returns how many "
            + "businesses were reminded.")
    @PostMapping("/billing/reminders")
    public int sendReminders(@RequestParam(required = false) java.time.LocalDate date) {
        return reminders.sendDue(date == null ? Dates.today() : date);
    }

    private Map<String, Object> account(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, is_super_admin from users where id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("account");
        }
        if (id.equals(CurrentUser.id())) {
            throw ApiException.rule("ADMIN_NOT_YOURSELF");
        }
        return rows.get(0);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
