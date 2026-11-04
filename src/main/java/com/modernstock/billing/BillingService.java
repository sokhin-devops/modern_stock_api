package com.modernstock.billing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Subscriptions and their lifecycle (PLAIN.md 11.13, FR-10.3 to FR-10.12):
 *
 * <pre>
 * Trial (Pro) ─┬─ subscribed → first invoice when the trial ends → paid: Active
 *              └─ not        → Free
 * Active ── period ends → renewal invoice, Past due (7 days, everything works) ─┬─ paid: Active
 *                                                                              └─ unpaid: Expired → Free
 * Active ── cancel → stays Active until the period ends → Free
 * </pre>
 *
 * Upgrades start a new period today at the new price (once paid); downgrades happen at the end of the period and are
 * blocked while usage is above the new limits. Whenever limits change, PlanLimits fits the branches and members.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    /** Past due lasts 7 days, then the plan expires to Free. */
    public static final Duration GRACE = Duration.ofDays(7);

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final BillingInvoiceRepository invoices;
    private final BillingPaymentRepository payments;
    private final PaymentGateway gateway;
    private final PlanLimits limits;
    private final EntitlementService entitlements;
    private final AppProperties props;
    private final TransactionTemplate tx;

    // ---------------------------------------------------------------- the Owner's actions (in the open business)

    /**
     * Subscribe to a paid plan. During the trial: the plan is chosen and billed when the trial ends (the remaining trial
     * days are kept). After a trial or plan ended (Free): an invoice for the first month, active once paid.
     */
    @Transactional
    public Subscription subscribe(String planCode) {
        Subscription s = mine();
        Plan plan = paidPlan(planCode);
        if (inTrial(s)) {
            choose(s, plan);
            return s;
        }
        if (s.getStatus() == Subscription.Status.ACTIVE || s.getStatus() == Subscription.Status.PAST_DUE) {
            throw ApiException.conflict("BILLING_ALREADY_SUBSCRIBED");
        }
        voidOpen(s.getBusinessId());
        Instant now = Instant.now();
        invoice(s.getBusinessId(), BillingInvoice.Kind.NEW, plan, now);
        return s;
    }

    /** Change plan: an upgrade bills a new period from today; a downgrade waits for the end of the period. */
    @Transactional
    public Subscription changePlan(String planCode) {
        Subscription s = mine();
        if (inTrial(s)) {
            choose(s, paidPlan(planCode));
            return s;
        }
        switch (s.getStatus()) {
            case PAST_DUE -> throw ApiException.conflict("BILLING_PAY_FIRST");
            case EXPIRED, CANCELLED, TRIALING -> {
                return subscribe(planCode);
            }
            default -> {
                // ACTIVE, below
            }
        }
        Plan current = plan(s.getPlanId());
        Plan target = plans.findByCode(planCode).orElseThrow(() -> ApiException.badRequest("BILLING_PLAN_INVALID"));
        if (target.getId().equals(current.getId())) {
            if (s.getPendingPlanId() == null) {
                throw ApiException.badRequest("BILLING_SAME_PLAN");
            }
            s.setPendingPlanId(null); // keep the current plan after all
            return s;
        }
        if (target.getPriceMonthly().compareTo(current.getPriceMonthly()) > 0) {
            voidOpen(s.getBusinessId());
            invoice(s.getBusinessId(), BillingInvoice.Kind.UPGRADE, target, Instant.now());
            return s;
        }
        checkFits(s.getBusinessId(), target);
        if (Plan.FREE.equals(target.getCode())) {
            s.setCancelAtPeriodEnd(true);
            s.setPendingPlanId(null);
        } else {
            s.setPendingPlanId(target.getId());
            s.setCancelAtPeriodEnd(false);
        }
        return s;
    }

    /** Stop renewing: the plan stays until the period ends (in the trial: no plan is billed when it ends). */
    @Transactional
    public Subscription cancel() {
        Subscription s = mine();
        if (inTrial(s)) {
            s.setSubscribed(false);
        } else if (s.getStatus() == Subscription.Status.ACTIVE) {
            s.setCancelAtPeriodEnd(true);
            s.setPendingPlanId(null);
        } else if (s.getStatus() == Subscription.Status.PAST_DUE) {
            toFree(s, Subscription.Status.CANCELLED);
        } else {
            throw ApiException.conflict("BILLING_NOTHING_TO_CANCEL");
        }
        return s;
    }

    /** Undo a cancel before the period ends. */
    @Transactional
    public Subscription resume() {
        Subscription s = mine();
        if (s.getStatus() != Subscription.Status.ACTIVE || !s.isCancelAtPeriodEnd()) {
            throw ApiException.conflict("BILLING_NOTHING_TO_RESUME");
        }
        s.setCancelAtPeriodEnd(false);
        return s;
    }

    /** Starts paying an open invoice through the gateway: the payment is Pending until the gateway confirms it. */
    @Transactional
    public BillingPayment pay(Long invoiceId) {
        BillingInvoice invoice = invoices.findById(invoiceId)
                .filter(i -> i.getBusinessId().equals(BusinessContext.businessId()))
                .orElseThrow(() -> ApiException.notFound("billing invoice"));
        if (invoice.getStatus() != BillingInvoice.Status.OPEN) {
            throw ApiException.conflict("BILLING_INVOICE_NOT_OPEN");
        }
        BillingPayment p = new BillingPayment();
        p.setBusinessId(invoice.getBusinessId());
        p.setInvoiceId(invoice.getId());
        p.setGateway(gateway.name());
        p.setReference(gateway.start(invoice));
        p.setAmount(invoice.getAmount());
        return payments.save(p);
    }

    /** The demo gateway's confirmation (only when billing.mode=demo): what a real gateway's callback would send. */
    @Transactional
    public BillingPayment confirmDemo(Long paymentId, boolean succeeded) {
        if (!demo()) {
            throw ApiException.forbidden("BILLING_NOT_DEMO");
        }
        BillingPayment p = payments.findById(paymentId)
                .filter(x -> x.getBusinessId().equals(BusinessContext.businessId()))
                .orElseThrow(() -> ApiException.notFound("billing payment"));
        return complete(p, succeeded, succeeded ? null : "Declined (demo)");
    }

    /** A gateway confirmed a payment: Succeeded pays the invoice and applies its plan; Failed leaves it open. */
    @Transactional
    public BillingPayment complete(BillingPayment p, boolean succeeded, String reason) {
        if (p.getStatus() != BillingPayment.Status.PENDING) {
            throw ApiException.conflict("BILLING_PAYMENT_DONE");
        }
        p.setCompletedAt(Instant.now());
        if (!succeeded) {
            p.setStatus(BillingPayment.Status.FAILED);
            p.setFailureReason(reason);
            return p;
        }
        BillingInvoice invoice = invoices.findById(p.getInvoiceId()).orElseThrow();
        if (invoice.getStatus() != BillingInvoice.Status.OPEN) {
            p.setStatus(BillingPayment.Status.FAILED);
            p.setFailureReason("The invoice is no longer open");
            return p;
        }
        p.setStatus(BillingPayment.Status.SUCCEEDED);
        invoice.setStatus(BillingInvoice.Status.PAID);
        invoice.setPaidAt(p.getCompletedAt());
        Subscription s = subscriptions.findByBusinessId(invoice.getBusinessId()).orElseThrow();
        Plan plan = plan(invoice.getPlanId());
        s.setStatus(Subscription.Status.ACTIVE);
        s.setPlanId(plan.getId());
        s.setSubscribed(true);
        s.setCurrentPeriodStart(invoice.getPeriodStart());
        s.setCurrentPeriodEnd(invoice.getPeriodEnd());
        s.setPastDueSince(null);
        s.setCancelAtPeriodEnd(false);
        s.setPendingPlanId(null);
        limits.apply(s.getBusinessId(), plan); // switches back on what a lower plan switched off
        return p;
    }

    public boolean demo() {
        return props.billing() != null && "demo".equalsIgnoreCase(props.billing().mode());
    }

    // ---------------------------------------------------------------- the lifecycle (jobs and demo controls)

    /** Every hour: trials that ended, periods that ended, and past-due plans whose 7 days are over. */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Phnom_Penh")
    public void tickAll() {
        List<Long> due = subscriptions.findAll().stream()
                .filter(s -> s.getStatus() == Subscription.Status.TRIALING || s.getStatus() == Subscription.Status.ACTIVE
                        || s.getStatus() == Subscription.Status.PAST_DUE)
                .map(Subscription::getBusinessId).toList();
        Instant now = Instant.now();
        for (Long businessId : due) {
            try {
                tx.executeWithoutResult(t -> tick(businessId, now));
            } catch (RuntimeException e) {
                log.warn("Billing lifecycle for business {} failed: {}", businessId, e.getMessage());
            }
        }
    }

    /** Moves one business's subscription on, if something is due at `now`. */
    @Transactional
    public void tick(Long businessId, Instant now) {
        Subscription s = subscriptions.findByBusinessId(businessId).orElseThrow();
        switch (s.getStatus()) {
            case TRIALING -> {
                if (s.getTrialEndsAt() != null && !s.getTrialEndsAt().isAfter(now)) {
                    if (s.isSubscribed()) {
                        // The first invoice comes when the trial ends; unpaid, it is past due like a renewal.
                        Plan chosen = plan(s.getPlanId());
                        invoice(businessId, BillingInvoice.Kind.NEW, chosen, s.getTrialEndsAt());
                        s.setStatus(Subscription.Status.PAST_DUE);
                        s.setPastDueSince(now);
                        limits.apply(businessId, chosen);
                    } else {
                        toFree(s, Subscription.Status.EXPIRED);
                    }
                }
            }
            case ACTIVE -> {
                if (s.getCurrentPeriodEnd() != null && !s.getCurrentPeriodEnd().isAfter(now)) {
                    if (s.isCancelAtPeriodEnd()) {
                        toFree(s, Subscription.Status.CANCELLED);
                    } else {
                        Plan next = plan(s.getPendingPlanId() != null ? s.getPendingPlanId() : s.getPlanId());
                        invoice(businessId, BillingInvoice.Kind.RENEWAL, next, s.getCurrentPeriodEnd());
                        s.setPlanId(next.getId()); // a downgrade happens here, at the end of the period
                        s.setPendingPlanId(null);
                        s.setStatus(Subscription.Status.PAST_DUE);
                        s.setPastDueSince(now);
                        limits.apply(businessId, next);
                    }
                }
            }
            case PAST_DUE -> {
                if (s.getPastDueSince() != null && !s.getPastDueSince().plus(GRACE).isAfter(now)) {
                    toFree(s, Subscription.Status.EXPIRED);
                }
            }
            default -> {
                // EXPIRED and CANCELLED stay on Free until the Owner subscribes again.
            }
        }
    }

    /** Demo control (super admin): the trial ends now. */
    @Transactional
    public void endTrialNow(Long businessId) {
        Subscription s = subscriptions.findByBusinessId(businessId).orElseThrow(() -> ApiException.notFound("subscription"));
        if (s.getStatus() != Subscription.Status.TRIALING) {
            throw ApiException.conflict("BILLING_NOT_TRIALING");
        }
        Instant now = Instant.now();
        s.setTrialEndsAt(now);
        tick(businessId, now);
    }

    /** Demo control (super admin): the period ends now (renewal), or the 7 past-due days are over (expiry). */
    @Transactional
    public void runRenewalNow(Long businessId) {
        Subscription s = subscriptions.findByBusinessId(businessId).orElseThrow(() -> ApiException.notFound("subscription"));
        Instant now = Instant.now();
        switch (s.getStatus()) {
            case ACTIVE -> s.setCurrentPeriodEnd(now);
            case PAST_DUE -> s.setPastDueSince(now.minus(GRACE));
            default -> throw ApiException.conflict("BILLING_NOTHING_TO_RENEW");
        }
        tick(businessId, now);
    }

    // ---------------------------------------------------------------- helpers

    private void toFree(Subscription s, Subscription.Status status) {
        Plan free = plans.findByCode(Plan.FREE).orElseThrow();
        voidOpen(s.getBusinessId());
        s.setStatus(status);
        s.setPlanId(free.getId());
        s.setSubscribed(false);
        s.setCancelAtPeriodEnd(false);
        s.setPendingPlanId(null);
        s.setPastDueSince(null);
        s.setCurrentPeriodEnd(Instant.now());
        limits.apply(s.getBusinessId(), free);
    }

    /** A month of `plan` from `start` (USD, the monthly price). */
    private BillingInvoice invoice(Long businessId, BillingInvoice.Kind kind, Plan plan, Instant start) {
        BillingInvoice i = new BillingInvoice();
        i.setBusinessId(businessId);
        i.setNumber("SUB-" + Year.now(Dates.PHNOM_PENH) + "-" + String.format("%06d", invoices.nextNumber()));
        i.setKind(kind);
        i.setPlanId(plan.getId());
        i.setPeriodStart(start);
        i.setPeriodEnd(ZonedDateTime.ofInstant(start, Dates.PHNOM_PENH).plusMonths(1).toInstant());
        i.setAmount(plan.getPriceMonthly());
        return invoices.save(i);
    }

    private void voidOpen(Long businessId) {
        invoices.findByBusinessIdAndStatus(businessId, BillingInvoice.Status.OPEN).forEach(i -> i.setStatus(BillingInvoice.Status.VOID));
    }

    /** A plan with lower limits is refused while the business uses more (the Owner first switches extras off). */
    private void checkFits(Long businessId, Plan plan) {
        for (String key : List.of(EntitlementService.BRANCHES, EntitlementService.MEMBERS, EntitlementService.PRODUCTS)) {
            Integer limit = plan.limit(key);
            long used = entitlements.usage(businessId, key);
            if (limit != null && used > limit) {
                throw ApiException.rule("BILLING_DOWNGRADE_OVER_LIMIT", key, used, limit);
            }
        }
    }

    /** During the trial: the plan billed when it ends. */
    private void choose(Subscription s, Plan plan) {
        checkFits(s.getBusinessId(), plan);
        s.setPlanId(plan.getId());
        s.setSubscribed(true);
        s.setPendingPlanId(null);
    }

    private boolean inTrial(Subscription s) {
        return s.getStatus() == Subscription.Status.TRIALING && s.getTrialEndsAt() != null && s.getTrialEndsAt().isAfter(Instant.now());
    }

    private Subscription mine() {
        return subscriptions.findByBusinessId(BusinessContext.businessId()).orElseThrow(() -> ApiException.notFound("subscription"));
    }

    private Plan plan(Long id) {
        return plans.findById(id).orElseThrow();
    }

    private Plan paidPlan(String code) {
        Plan plan = plans.findByCode(code).orElseThrow(() -> ApiException.badRequest("BILLING_PLAN_INVALID"));
        if (plan.getPriceMonthly().compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("BILLING_PLAN_INVALID");
        }
        return plan;
    }
}
