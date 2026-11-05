package com.modernstock.billing;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * The only place that answers "can this business do X?" (PLAIN.md 11.13). Effective limits come from the plan;
 * during the trial the business gets Pro, and after a plan ends it falls back to Free. Nothing is deleted.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    public static final String BRANCHES = "branches";
    public static final String MEMBERS = "members";
    public static final String PRODUCTS = "products";
    public static final String TELEGRAM_ALERTS = "telegram_alerts";
    public static final String EXCEL_EXPORT = "excel_export";
    public static final String TAX_INVOICE = "tax_invoice";

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Plan effectivePlan(Long businessId) {
        Subscription s = subscriptions.findByBusinessId(businessId).orElse(null);
        if (s == null) {
            return plan(Plan.FREE);
        }
        return switch (s.getStatus()) {
            case TRIALING -> s.getTrialEndsAt() != null && s.getTrialEndsAt().isAfter(Instant.now())
                    ? plan(Plan.PRO) : plan(Plan.FREE);
            case ACTIVE, PAST_DUE -> plans.findById(s.getPlanId()).orElseThrow();
            case EXPIRED, CANCELLED -> plan(Plan.FREE);
        };
    }

    /** A limit with the business's active add-ons (FR-10.2): null = unlimited; a feature is on above 0. */
    @Transactional(readOnly = true)
    public Integer limit(Long businessId, String key) {
        return limit(businessId, effectivePlan(businessId), key);
    }

    /** The same for a given plan, e.g. the one a downgrade moves to. */
    public Integer limit(Long businessId, Plan plan, String key) {
        Integer base = plan.limit(key);
        if (base == null) {
            return null;
        }
        Long extra = jdbc.queryForObject("""
                SELECT coalesce(sum(sa.quantity * a.quantity), 0) FROM subscription_addons sa JOIN addons a ON a.id = sa.addon_id
                 WHERE sa.business_id = ? AND sa.status = 'ACTIVE' AND a.limit_key = ?
                """, Long.class, businessId, key);
        return base + (extra == null ? 0 : extra.intValue());
    }

    /** Throws 422 PLAN_LIMIT_REACHED before adding one more branch, member or product. */
    public void checkCanAdd(String key) {
        Long businessId = BusinessContext.require().businessId();
        Integer limit = limit(businessId, key);
        if (limit != null && usage(businessId, key) >= limit) {
            throw ApiException.rule("PLAN_LIMIT_REACHED", key, limit);
        }
    }

    public boolean hasFeature(String key) {
        return hasFeature(BusinessContext.require().businessId(), key);
    }

    /** For jobs that run outside a request (the daily Telegram summary). */
    public boolean hasFeature(Long businessId, String key) {
        Integer value = limit(businessId, key);
        return value == null || value > 0;
    }

    /** Pending invitations count as members, so an Owner can't invite past the limit. */
    public long usage(Long businessId, String key) {
        String sql = switch (key) {
            case BRANCHES -> "SELECT count(*) FROM branches WHERE business_id = ? AND is_active AND NOT read_only";
            case MEMBERS -> """
                    SELECT (SELECT count(*) FROM business_members WHERE business_id = ? AND status = 'ACTIVE')
                         + (SELECT count(*) FROM invitations WHERE business_id = ? AND status = 'PENDING' AND expires_at > now())
                    """;
            case PRODUCTS -> "SELECT count(*) FROM products WHERE business_id = ? AND is_active";
            default -> null;
        };
        if (sql == null) {
            return 0;
        }
        Object[] args = MEMBERS.equals(key) ? new Object[] {businessId, businessId} : new Object[] {businessId};
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private Plan plan(String code) {
        return plans.findByCode(code).orElseThrow(() -> new IllegalStateException("Plan " + code + " is not seeded"));
    }
}
