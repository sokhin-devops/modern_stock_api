package com.modernstock.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.common.FileStorage;
import com.modernstock.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Subscriptions and their lifecycle (PLAIN.md 11.13, FR-10.3 to FR-10.18):
 *
 * <pre>
 * Trial (Pro) ─┬─ subscribed → first invoice when the trial ends → paid: Active
 *              └─ not        → Free
 * Active ── period ends → renewal invoice, Past due (7 days, everything works) ─┬─ paid: Active
 *                                                                              └─ unpaid: Expired → Free
 * Active ── cancel → stays Active until the period ends → Free
 * </pre>
 *
 * A plan is paid monthly or yearly (10 months' price, FR-10.13). An upgrade, or an add-on, in the middle of a period
 * charges only the difference for the days left (FR-10.17) and takes effect once paid; downgrades and cycle changes
 * happen at the end of the period, and downgrades are blocked while usage is above the new limits. Add-ons renew with
 * the plan until removed (FR-10.6). An invoice is paid through the gateway or by a bank transfer the super admin
 * approves (FR-10.14). Whenever limits change, PlanLimits fits the branches and members.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    /** Past due lasts 7 days, then the plan expires to Free. */
    public static final Duration GRACE = Duration.ofDays(7);
    public static final String BANK_TRANSFER = "BANK_TRANSFER";
    public static final List<String> BANKS = List.of("ABA", "ACLEDA", "KHQR", "OTHER");
    private static final List<String> COUNTED = List.of(EntitlementService.BRANCHES, EntitlementService.MEMBERS, EntitlementService.PRODUCTS);

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final BillingInvoiceRepository invoices;
    private final BillingPaymentRepository payments;
    private final AddonRepository addons;
    private final SubscriptionAddonRepository subscriptionAddons;
    private final PaymentGateway gateway;
    private final PlanLimits limits;
    private final EntitlementService entitlements;
    private final FileStorage files;
    private final AppProperties props;
    private final TransactionTemplate tx;

    // ---------------------------------------------------------------- the Owner's actions (in the open business)

    /**
     * Subscribe to a paid plan, monthly or yearly. During the trial: the plan is chosen and billed when the trial ends
     * (the remaining trial days are kept). After a trial or plan ended (Free): an invoice for the first period, active
     * once paid.
     */
    @Transactional
    public Subscription subscribe(String planCode, Subscription.Cycle cycle) {
        Subscription s = mine();
        Plan plan = paidPlan(planCode);
        Subscription.Cycle chosen = cycle == null ? Subscription.Cycle.MONTHLY : cycle;
        if (inTrial(s)) {
            choose(s, plan);
            s.setBillingCycle(chosen);
            return s;
        }
        if (s.getStatus() == Subscription.Status.ACTIVE || s.getStatus() == Subscription.Status.PAST_DUE) {
            throw ApiException.conflict("BILLING_ALREADY_SUBSCRIBED");
        }
        voidOpen(s.getBusinessId(), EnumSet.allOf(BillingInvoice.Kind.class));
        invoice(s.getBusinessId(), BillingInvoice.Kind.NEW, plan, Instant.now(), chosen);
        return s;
    }

    /**
     * Change plan or cycle. An upgrade charges the price difference for the days left of the period (an invoice; the
     * plan changes once it is paid); a downgrade, and a change between monthly and yearly, wait for the end of the period.
     */
    @Transactional
    public Subscription changePlan(String planCode, Subscription.Cycle cycle) {
        Subscription s = mine();
        if (inTrial(s)) {
            choose(s, paidPlan(planCode));
            if (cycle != null) {
                s.setBillingCycle(cycle);
            }
            return s;
        }
        switch (s.getStatus()) {
            case PAST_DUE -> throw ApiException.conflict("BILLING_PAY_FIRST");
            case EXPIRED, CANCELLED, TRIALING -> {
                return subscribe(planCode, cycle);
            }
            default -> {
                // ACTIVE, below
            }
        }
        Plan current = plan(s.getPlanId());
        Plan target = plans.findByCode(planCode).orElseThrow(() -> ApiException.badRequest("BILLING_PLAN_INVALID"));
        boolean cycleChange = cycle != null && cycle != s.getBillingCycle();
        if (target.getId().equals(current.getId())) {
            if (cycleChange) {
                s.setPendingBillingCycle(cycle);
                return s;
            }
            if (s.getPendingPlanId() == null && s.getPendingBillingCycle() == null) {
                throw ApiException.badRequest("BILLING_SAME_PLAN");
            }
            s.setPendingPlanId(null); // keep the current plan (and cycle) after all
            s.setPendingBillingCycle(null);
            return s;
        }
        s.setPendingBillingCycle(cycleChange ? cycle : null);
        if (target.getPriceMonthly().compareTo(current.getPriceMonthly()) > 0) {
            voidOpen(s.getBusinessId(), EnumSet.of(BillingInvoice.Kind.UPGRADE));
            Instant now = Instant.now();
            BigDecimal share = remaining(s, now);
            BigDecimal amount = price(target, s.getBillingCycle()).subtract(price(current, s.getBillingCycle())).multiply(share)
                    .setScale(2, RoundingMode.HALF_UP);
            BillingInvoice i = newInvoice(s.getBusinessId(), BillingInvoice.Kind.UPGRADE, target, now, s.getCurrentPeriodEnd(),
                    s.getBillingCycle(), amount, target.getName() + " − " + current.getName() + ", " + daysLeft(s, now) + " days");
            s.setPendingPlanId(null);
            if (amount.signum() == 0) {
                paid(i, now); // nothing left to pay for: the upgrade applies now
            }
            return s;
        }
        checkFits(s.getBusinessId(), target);
        if (Plan.FREE.equals(target.getCode())) {
            s.setCancelAtPeriodEnd(true);
            s.setPendingPlanId(null);
            s.setPendingBillingCycle(null);
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
            s.setPendingBillingCycle(null);
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

    /**
     * Buy an add-on now (FR-10.6): an invoice for the days left of the period; it works once paid and then renews
     * with the plan. Only on an active paid plan.
     */
    @Transactional
    public SubscriptionAddon addAddon(String code, int quantity) {
        Subscription s = mine();
        if (s.getStatus() != Subscription.Status.ACTIVE || s.isCancelAtPeriodEnd()) {
            throw ApiException.rule("BILLING_ADDON_NEEDS_PLAN");
        }
        Addon addon = addons.findByCode(code).filter(Addon::isActive).orElseThrow(() -> ApiException.badRequest("BILLING_ADDON_INVALID"));
        boolean feature = !COUNTED.contains(addon.getLimitKey());
        if (quantity < 1 || quantity > 50 || (feature && quantity != 1)) {
            throw ApiException.badRequest("BILLING_ADDON_INVALID");
        }
        if (feature ? entitlements.hasFeature(s.getBusinessId(), addon.getLimitKey())
                : plan(s.getPlanId()).limit(addon.getLimitKey()) == null) {
            throw ApiException.rule("BILLING_ADDON_NOT_NEEDED");
        }
        SubscriptionAddon sa = new SubscriptionAddon();
        sa.setBusinessId(s.getBusinessId());
        sa.setAddonId(addon.getId());
        sa.setQuantity(quantity);
        subscriptionAddons.save(sa);
        Instant now = Instant.now();
        BigDecimal amount = addon.price(s.getBillingCycle()).multiply(BigDecimal.valueOf(quantity)).multiply(remaining(s, now))
                .setScale(2, RoundingMode.HALF_UP);
        BillingInvoice i = newInvoice(s.getBusinessId(), BillingInvoice.Kind.ADDON, plan(s.getPlanId()), now, s.getCurrentPeriodEnd(),
                s.getBillingCycle(), amount, addon.getName() + (quantity > 1 ? " ×" + quantity : "") + ", " + daysLeft(s, now) + " days");
        i.setSubscriptionAddonId(sa.getId());
        if (amount.signum() == 0) {
            paid(i, now);
        }
        return sa;
    }

    /** Remove an add-on: one not paid yet goes now; an active one ends with the period and is not renewed. */
    @Transactional
    public void removeAddon(Long id) {
        SubscriptionAddon sa = myAddon(id);
        switch (sa.getStatus()) {
            case PENDING -> {
                invoices.findByBusinessIdAndStatus(sa.getBusinessId(), BillingInvoice.Status.OPEN).stream()
                        .filter(i -> sa.getId().equals(i.getSubscriptionAddonId()))
                        .forEach(i -> i.setStatus(BillingInvoice.Status.VOID));
                end(sa);
            }
            case ACTIVE -> sa.setRemoveAtPeriodEnd(true);
            default -> throw ApiException.conflict("BILLING_ADDON_ENDED");
        }
    }

    /** Undo a removal before the period ends. */
    @Transactional
    public void keepAddon(Long id) {
        SubscriptionAddon sa = myAddon(id);
        if (sa.getStatus() != SubscriptionAddon.Status.ACTIVE || !sa.isRemoveAtPeriodEnd()) {
            throw ApiException.conflict("BILLING_ADDON_ENDED");
        }
        sa.setRemoveAtPeriodEnd(false);
    }

    /** Starts paying an open invoice through the gateway: the payment is Pending until the gateway confirms it. */
    @Transactional
    public BillingPayment pay(Long invoiceId) {
        BillingInvoice invoice = myOpenInvoice(invoiceId);
        BillingPayment p = new BillingPayment();
        p.setBusinessId(invoice.getBusinessId());
        p.setInvoiceId(invoice.getId());
        p.setGateway(gateway.name());
        p.setReference(gateway.start(invoice));
        p.setAmount(invoice.getAmount());
        return payments.save(p);
    }

    /** Pays an open invoice by bank transfer (FR-10.14): the Owner's screenshot waits for the super admin to approve it. */
    @Transactional
    public BillingPayment transfer(Long invoiceId, String bank, MultipartFile proof) {
        BillingInvoice invoice = myOpenInvoice(invoiceId);
        if (transferAccounts().isEmpty()) {
            throw ApiException.rule("BILLING_TRANSFER_OFF");
        }
        if (bank == null || !BANKS.contains(bank)) {
            throw ApiException.badRequest("BILLING_BANK_INVALID");
        }
        boolean waiting = payments.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).stream()
                .anyMatch(p -> BANK_TRANSFER.equals(p.getGateway()) && p.getStatus() == BillingPayment.Status.PENDING);
        if (waiting) {
            throw ApiException.conflict("BILLING_TRANSFER_PENDING");
        }
        BillingPayment p = new BillingPayment();
        p.setBusinessId(invoice.getBusinessId());
        p.setInvoiceId(invoice.getId());
        p.setGateway(BANK_TRANSFER);
        p.setReference("BT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        p.setBank(bank);
        p.setProofPath(files.saveImage(proof, FileStorage.PRIVATE_TRANSFERS));
        p.setAmount(invoice.getAmount());
        return payments.save(p);
    }

    /** The super admin approves (the invoice is paid) or rejects (with the reason the Owner sees) a bank transfer. */
    @Transactional
    public BillingPayment review(Long paymentId, boolean approve, String reason, Long adminId) {
        BillingPayment p = payments.findById(paymentId).filter(x -> BANK_TRANSFER.equals(x.getGateway()))
                .orElseThrow(() -> ApiException.notFound("bank transfer"));
        p.setReviewedBy(adminId);
        return complete(p, approve, approve ? null : (reason == null || reason.isBlank() ? "Rejected" : reason.trim()));
    }

    /** The demo gateway's confirmation (only when billing.mode=demo): what a real gateway's callback would send. */
    @Transactional
    public BillingPayment confirmDemo(Long paymentId, boolean succeeded) {
        if (!demo()) {
            throw ApiException.forbidden("BILLING_NOT_DEMO");
        }
        BillingPayment p = payments.findById(paymentId)
                .filter(x -> x.getBusinessId().equals(BusinessContext.businessId()) && !BANK_TRANSFER.equals(x.getGateway()))
                .orElseThrow(() -> ApiException.notFound("billing payment"));
        return complete(p, succeeded, succeeded ? null : "Declined (demo)");
    }

    /** A payment was confirmed: Succeeded pays the invoice and applies what it is for; Failed leaves it open. */
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
        paid(invoice, p.getCompletedAt());
        return p;
    }

    public boolean demo() {
        return props.billing() != null && "demo".equalsIgnoreCase(props.billing().mode());
    }

    /** Who issues the billing invoices; ModernStock with no details when not configured. */
    public AppProperties.Company company() {
        AppProperties.Company c = props.billing() == null ? null : props.billing().company();
        return c == null || c.name() == null || c.name().isBlank() ? new AppProperties.Company("ModernStock", null, null, null, null) : c;
    }

    /** Where owners send bank transfers; none = transfers are not offered. */
    public List<AppProperties.TransferAccount> transferAccounts() {
        return props.billing() == null || props.billing().transferAccounts() == null ? List.of() : props.billing().transferAccounts();
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
                        invoice(businessId, BillingInvoice.Kind.NEW, chosen, s.getTrialEndsAt(), s.getBillingCycle());
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
                        // What was bought for the period that ended no longer applies: unpaid upgrades and add-ons go;
                        // add-ons removed (or now part of the plan) end; the rest renews with the plan.
                        voidOpen(businessId, EnumSet.of(BillingInvoice.Kind.UPGRADE, BillingInvoice.Kind.ADDON));
                        Plan next = plan(s.getPendingPlanId() != null ? s.getPendingPlanId() : s.getPlanId());
                        stopCovered(businessId, next);
                        subscriptionAddons.findByBusinessIdAndStatusInOrderById(businessId, List.of(SubscriptionAddon.Status.ACTIVE))
                                .stream().filter(SubscriptionAddon::isRemoveAtPeriodEnd).forEach(this::end);
                        Subscription.Cycle cycle = s.getPendingBillingCycle() != null ? s.getPendingBillingCycle() : s.getBillingCycle();
                        invoice(businessId, BillingInvoice.Kind.RENEWAL, next, s.getCurrentPeriodEnd(), cycle);
                        s.setPlanId(next.getId()); // a downgrade happens here, at the end of the period
                        s.setBillingCycle(cycle);
                        s.setPendingPlanId(null);
                        s.setPendingBillingCycle(null);
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

    /** A paid invoice takes effect: a new period, a plan upgraded for the rest of the period, or an add-on switched on. */
    private void paid(BillingInvoice invoice, Instant at) {
        invoice.setStatus(BillingInvoice.Status.PAID);
        invoice.setPaidAt(at);
        Subscription s = subscriptions.findByBusinessId(invoice.getBusinessId()).orElseThrow();
        switch (invoice.getKind()) {
            case ADDON -> {
                SubscriptionAddon sa = subscriptionAddons.findById(invoice.getSubscriptionAddonId()).orElseThrow();
                sa.setStatus(SubscriptionAddon.Status.ACTIVE);
                sa.setStartedAt(at);
            }
            case UPGRADE -> {
                s.setPlanId(invoice.getPlanId());
                s.setStatus(Subscription.Status.ACTIVE);
                s.setCurrentPeriodEnd(invoice.getPeriodEnd());
                s.setPendingPlanId(null);
                stopCovered(s.getBusinessId(), plan(invoice.getPlanId()));
            }
            default -> {
                s.setStatus(Subscription.Status.ACTIVE);
                s.setPlanId(invoice.getPlanId());
                s.setBillingCycle(invoice.getBillingCycle());
                s.setSubscribed(true);
                s.setCurrentPeriodStart(invoice.getPeriodStart());
                s.setCurrentPeriodEnd(invoice.getPeriodEnd());
                s.setPastDueSince(null);
                s.setCancelAtPeriodEnd(false);
                s.setPendingPlanId(null);
            }
        }
        limits.apply(s.getBusinessId(), plan(s.getPlanId())); // switches back on what a lower limit switched off
    }

    private void toFree(Subscription s, Subscription.Status status) {
        Plan free = plans.findByCode(Plan.FREE).orElseThrow();
        voidOpen(s.getBusinessId(), EnumSet.allOf(BillingInvoice.Kind.class));
        subscriptionAddons.findByBusinessIdAndStatusInOrderById(s.getBusinessId(),
                List.of(SubscriptionAddon.Status.PENDING, SubscriptionAddon.Status.ACTIVE)).forEach(this::end);
        s.setStatus(status);
        s.setPlanId(free.getId());
        s.setSubscribed(false);
        s.setCancelAtPeriodEnd(false);
        s.setPendingPlanId(null);
        s.setPendingBillingCycle(null);
        s.setPastDueSince(null);
        s.setCurrentPeriodEnd(Instant.now());
        limits.apply(s.getBusinessId(), free);
    }

    /** A full period of `plan` from `start`, with the add-ons that renew with it. */
    private BillingInvoice invoice(Long businessId, BillingInvoice.Kind kind, Plan plan, Instant start, Subscription.Cycle cycle) {
        ZonedDateTime from = ZonedDateTime.ofInstant(start, Dates.PHNOM_PENH);
        Instant end = (cycle == Subscription.Cycle.YEARLY ? from.plusYears(1) : from.plusMonths(1)).toInstant();
        BigDecimal amount = price(plan, cycle);
        List<String> what = new ArrayList<>();
        what.add(plan.getName() + (cycle == Subscription.Cycle.YEARLY ? ", yearly" : ", monthly"));
        for (SubscriptionAddon sa : subscriptionAddons.findByBusinessIdAndStatusInOrderById(businessId, List.of(SubscriptionAddon.Status.ACTIVE))) {
            Addon addon = addons.findById(sa.getAddonId()).orElseThrow();
            amount = amount.add(addon.price(cycle).multiply(BigDecimal.valueOf(sa.getQuantity())));
            what.add(addon.getName() + (sa.getQuantity() > 1 ? " ×" + sa.getQuantity() : ""));
        }
        return newInvoice(businessId, kind, plan, start, end, cycle, amount, String.join(" + ", what));
    }

    private BillingInvoice newInvoice(Long businessId, BillingInvoice.Kind kind, Plan plan, Instant start, Instant end,
                                      Subscription.Cycle cycle, BigDecimal amount, String description) {
        BillingInvoice i = new BillingInvoice();
        i.setBusinessId(businessId);
        i.setNumber("SUB-" + Year.now(Dates.PHNOM_PENH) + "-" + String.format("%06d", invoices.nextNumber()));
        i.setKind(kind);
        i.setPlanId(plan.getId());
        i.setPeriodStart(start);
        i.setPeriodEnd(end);
        i.setBillingCycle(cycle);
        i.setAmount(amount);
        i.setDescription(description.length() > 255 ? description.substring(0, 255) : description);
        return invoices.save(i);
    }

    /** The plan's price for a period: a year costs 10 months unless the plan has its own yearly price. */
    static BigDecimal price(Plan plan, Subscription.Cycle cycle) {
        if (cycle != Subscription.Cycle.YEARLY) {
            return plan.getPriceMonthly();
        }
        return plan.getPriceYearly() != null ? plan.getPriceYearly() : plan.getPriceMonthly().multiply(BigDecimal.TEN);
    }

    /** The share of the current period still to come, 0 to 1 (FR-10.17). */
    private static BigDecimal remaining(Subscription s, Instant now) {
        if (s.getCurrentPeriodStart() == null || s.getCurrentPeriodEnd() == null) {
            return BigDecimal.ONE;
        }
        long total = Duration.between(s.getCurrentPeriodStart(), s.getCurrentPeriodEnd()).toSeconds();
        long left = Duration.between(now, s.getCurrentPeriodEnd()).toSeconds();
        if (total <= 0 || left <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.min(left, total)).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    private static long daysLeft(Subscription s, Instant now) {
        return s.getCurrentPeriodEnd() == null ? 0 : (long) Math.ceil(Math.max(0, Duration.between(now, s.getCurrentPeriodEnd()).toSeconds()) / 86400.0);
    }

    /** Voids open invoices of these kinds; an add-on whose invoice goes ends with it. */
    private void voidOpen(Long businessId, EnumSet<BillingInvoice.Kind> kinds) {
        invoices.findByBusinessIdAndStatus(businessId, BillingInvoice.Status.OPEN).stream()
                .filter(i -> kinds.contains(i.getKind()))
                .forEach(i -> {
                    i.setStatus(BillingInvoice.Status.VOID);
                    if (i.getSubscriptionAddonId() != null) {
                        subscriptionAddons.findById(i.getSubscriptionAddonId())
                                .filter(sa -> sa.getStatus() == SubscriptionAddon.Status.PENDING).ifPresent(this::end);
                    }
                });
    }

    /** A feature add-on the plan now includes is not renewed: the Owner doesn't pay twice for it. */
    private void stopCovered(Long businessId, Plan plan) {
        subscriptionAddons.findByBusinessIdAndStatusInOrderById(businessId, List.of(SubscriptionAddon.Status.ACTIVE)).forEach(sa -> {
            Addon addon = addons.findById(sa.getAddonId()).orElseThrow();
            Integer included = plan.limit(addon.getLimitKey());
            if (!COUNTED.contains(addon.getLimitKey()) && (included == null || included > 0)) {
                sa.setRemoveAtPeriodEnd(true);
            }
        });
    }

    private void end(SubscriptionAddon sa) {
        sa.setStatus(SubscriptionAddon.Status.ENDED);
        sa.setEndedAt(Instant.now());
        sa.setRemoveAtPeriodEnd(false);
    }

    /** A plan with lower limits is refused while the business uses more (the Owner first switches extras off). */
    private void checkFits(Long businessId, Plan plan) {
        for (String key : COUNTED) {
            Integer limit = entitlements.limit(businessId, plan, key);
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

    private BillingInvoice myOpenInvoice(Long invoiceId) {
        BillingInvoice invoice = invoices.findById(invoiceId)
                .filter(i -> i.getBusinessId().equals(BusinessContext.businessId()))
                .orElseThrow(() -> ApiException.notFound("billing invoice"));
        if (invoice.getStatus() != BillingInvoice.Status.OPEN) {
            throw ApiException.conflict("BILLING_INVOICE_NOT_OPEN");
        }
        return invoice;
    }

    private SubscriptionAddon myAddon(Long id) {
        return subscriptionAddons.findById(id).filter(sa -> sa.getBusinessId().equals(BusinessContext.businessId()))
                .orElseThrow(() -> ApiException.notFound("add-on"));
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
