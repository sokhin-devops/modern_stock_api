package com.modernstock.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BusinessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

/**
 * Billing for Owners (FR-10.12): plan, status, usage, plans to choose, billing invoices, and the demo payment. See
 * BillingService for the lifecycle.
 */
@Tag(name = "Billing")
@RestController
@RequestMapping("/billing")
@PreAuthorize("hasAuthority('billing:manage')")
@RequiredArgsConstructor
public class BillingController {

    public record Usage(String key, Integer limit, long used) {
    }

    public record PlanView(String code, String name, BigDecimal priceMonthly, Map<String, Integer> limits) {
    }

    public record InvoiceView(Long id, String number, BillingInvoice.Kind kind, String planCode, String planName,
                              Instant periodStart, Instant periodEnd, BigDecimal amount, BillingInvoice.Status status,
                              Instant createdAt, Instant paidAt) {
    }

    public record PaymentView(Long id, Long invoiceId, String gateway, String reference, BigDecimal amount,
                              BillingPayment.Status status, String failureReason) {
    }

    public record BillingResponse(String planCode, String planName, String effectivePlanCode, Subscription.Status status,
                                  Subscription.Cycle billingCycle, Instant trialEndsAt, Instant currentPeriodEnd,
                                  boolean cancelAtPeriodEnd, List<Usage> usage,
                                  @Schema(description = "A paid plan is chosen (in the trial: billed when it ends)") boolean subscribed,
                                  @Schema(description = "Plan that starts at the end of the period (a downgrade)") String pendingPlanCode,
                                  @Schema(description = "Past due: pay before this, or the plan expires to Free") Instant pastDueUntil,
                                  @Schema(description = "Demo payment buttons are on (billing.mode=demo)") boolean demo,
                                  InvoiceView openInvoice, List<PlanView> plans) {
    }

    public record PlanRequest(@NotBlank String planCode) {
    }

    public record DemoConfirm(@Schema(description = "true = Pay (demo), false = Fail payment (demo)") boolean succeed) {
    }

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final BillingInvoiceRepository invoices;
    private final BillingPaymentRepository payments;
    private final EntitlementService entitlements;
    private final BillingService billing;

    @Operation(summary = "Plan, status, usage and the plans to choose",
            description = "effectivePlanCode is what the limits come from: PRO during the trial, FREE after a plan ends. limit null = unlimited.")
    @GetMapping
    @Transactional(readOnly = true)
    public BillingResponse getBilling() {
        Long businessId = BusinessContext.businessId();
        Subscription s = subscriptions.findByBusinessId(businessId).orElseThrow();
        Plan plan = plans.findById(s.getPlanId()).orElseThrow();
        Plan effective = entitlements.effectivePlan(businessId);
        List<Usage> usage = effective.getLimits().stream()
                .map(l -> new Usage(l.getLimitKey(), l.getValue(), entitlements.usage(businessId, l.getLimitKey())))
                .toList();
        InvoiceView open = invoices.findByBusinessIdAndStatus(businessId, BillingInvoice.Status.OPEN).stream()
                .findFirst().map(this::view).orElse(null);
        return new BillingResponse(plan.getCode(), plan.getName(), effective.getCode(), s.getStatus(), s.getBillingCycle(),
                s.getTrialEndsAt(), s.getCurrentPeriodEnd(), s.isCancelAtPeriodEnd(), usage, s.isSubscribed(),
                s.getPendingPlanId() == null ? null : plans.findById(s.getPendingPlanId()).map(Plan::getCode).orElse(null),
                s.getPastDueSince() == null ? null : s.getPastDueSince().plus(BillingService.GRACE), billing.demo(), open,
                plans.findByPublicPlanTrueOrderBySortOrder().stream().map(BillingController::planView).toList());
    }

    @Operation(summary = "Billing invoices (SUB-…)", description = "Newest first.")
    @GetMapping("/invoices")
    @Transactional(readOnly = true)
    public List<InvoiceView> billingInvoices() {
        return invoices.findByBusinessIdOrderByCreatedAtDesc(BusinessContext.businessId()).stream().map(this::view).toList();
    }

    @Operation(summary = "Subscribe to a paid plan",
            description = "In the trial: chosen, billed when the trial ends. After it: an invoice for the first month, active once paid.")
    @PostMapping("/subscribe")
    public BillingResponse subscribe(@Valid @RequestBody PlanRequest req) {
        billing.subscribe(req.planCode());
        return getBilling();
    }

    @Operation(summary = "Change plan",
            description = "Upgrade: an invoice for a new period from today. Downgrade (FREE = cancel): at the end of the period; "
                    + "422 BILLING_DOWNGRADE_OVER_LIMIT while usage is above the new limits.")
    @PostMapping("/change-plan")
    public BillingResponse changePlan(@Valid @RequestBody PlanRequest req) {
        billing.changePlan(req.planCode());
        return getBilling();
    }

    @Operation(summary = "Cancel", description = "Renewal stops; the plan stays until the period ends.")
    @PostMapping("/cancel")
    public BillingResponse cancelSubscription() {
        billing.cancel();
        return getBilling();
    }

    @Operation(summary = "Keep the plan after a cancel")
    @PostMapping("/resume")
    public BillingResponse resumeSubscription() {
        billing.resume();
        return getBilling();
    }

    @Operation(summary = "Pay an open billing invoice", description = "Starts a payment through the gateway; it stays PENDING until confirmed.")
    @PostMapping("/invoices/{id}/pay")
    public PaymentView payInvoice(@PathVariable Long id) {
        return view(billing.pay(id));
    }

    @Operation(summary = "Demo gateway confirmation",
            description = "Pay (demo) or Fail payment (demo). Only when billing.mode=demo; no money is charged.")
    @PostMapping("/payments/{id}/demo")
    public PaymentView confirmDemoPayment(@PathVariable Long id, @RequestBody DemoConfirm req) {
        return view(billing.confirmDemo(id, req.succeed()));
    }

    private InvoiceView view(BillingInvoice i) {
        Plan plan = plans.findById(i.getPlanId()).orElseThrow();
        return new InvoiceView(i.getId(), i.getNumber(), i.getKind(), plan.getCode(), plan.getName(), i.getPeriodStart(),
                i.getPeriodEnd(), i.getAmount(), i.getStatus(), i.getCreatedAt(), i.getPaidAt());
    }

    private PaymentView view(BillingPayment p) {
        return new PaymentView(p.getId(), p.getInvoiceId(), p.getGateway(), p.getReference(), p.getAmount(), p.getStatus(),
                p.getFailureReason());
    }

    static PlanView planView(Plan p) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        p.getLimits().forEach(l -> limits.put(l.getLimitKey(), l.getValue()));
        return new PlanView(p.getCode(), p.getName(), p.getPriceMonthly(), limits);
    }
}
