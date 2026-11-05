package com.modernstock.billing;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.modernstock.activity.ActivityService;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.FileStorage;
import com.modernstock.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/**
 * Billing for Owners (FR-10.12): plan, status, usage, plans and add-ons to choose, billing invoices, payment (demo
 * gateway or bank transfer), and which branches and members stay active when limits go down. See BillingService for the
 * lifecycle.
 */
@Tag(name = "Billing")
@RestController
@RequestMapping("/billing")
@PreAuthorize("hasAuthority('billing:manage')")
@RequiredArgsConstructor
public class BillingController {

    public record Usage(String key, @Schema(description = "With add-ons; null = unlimited") Integer limit, long used) {
    }

    public record PlanView(String code, String name, BigDecimal priceMonthly,
                           @Schema(description = "A year: 10 months' price") BigDecimal priceYearly, Map<String, Integer> limits) {
    }

    public record AddonView(String code, String name, String nameKm, String limitKey, int quantity, BigDecimal priceMonthly, BigDecimal priceYearly) {
    }

    public record MyAddon(Long id, String code, String name, String nameKm, String limitKey, int quantity, SubscriptionAddon.Status status,
                          @Schema(description = "Removed: ends with the period") boolean removeAtPeriodEnd,
                          @Schema(description = "For this subscription's cycle") BigDecimal price) {
    }

    public record InvoiceView(Long id, String number, BillingInvoice.Kind kind, String planCode, String planName,
                              Subscription.Cycle billingCycle, String description, Instant periodStart, Instant periodEnd,
                              BigDecimal amount, BillingInvoice.Status status, Instant createdAt, Instant paidAt,
                              @Schema(description = "A bank transfer waits for the super admin") boolean transferPending,
                              @Schema(description = "Why the last bank transfer was rejected") String transferRejected,
                              @Schema(description = "The payment that paid it (for the receipt)") PaymentView paidWith,
                              @Schema(description = "Taken off by a coupon (FR-10.16)") BigDecimal discount, String couponCode) {
    }

    public record PaymentView(Long id, Long invoiceId, String gateway, String reference, BigDecimal amount,
                              BillingPayment.Status status, String failureReason, String bank) {
    }

    public record BillingResponse(String planCode, String planName, String effectivePlanCode, Subscription.Status status,
                                  Subscription.Cycle billingCycle,
                                  @Schema(description = "Cycle from the next period") Subscription.Cycle pendingBillingCycle,
                                  Instant trialEndsAt, Instant currentPeriodEnd,
                                  boolean cancelAtPeriodEnd, List<Usage> usage,
                                  @Schema(description = "A paid plan is chosen (in the trial: billed when it ends)") boolean subscribed,
                                  @Schema(description = "Plan that starts at the end of the period (a downgrade)") String pendingPlanCode,
                                  @Schema(description = "Past due: pay before this, or the plan expires to Free") Instant pastDueUntil,
                                  @Schema(description = "Demo payment buttons are on (billing.mode=demo)") boolean demo,
                                  @Schema(description = "The first open invoice (see openInvoices)") InvoiceView openInvoice,
                                  List<InvoiceView> openInvoices, List<PlanView> plans, List<AddonView> addons,
                                  List<MyAddon> myAddons, List<String> banks,
                                  @Schema(description = "Where to send a bank transfer; empty = transfers are off") List<AppProperties.TransferAccount> transferAccounts,
                                  @Schema(description = "Who issues the billing invoices") AppProperties.Company issuer,
                                  @Schema(description = "The coupon running: its discount on the next full-period invoices") CouponService.Running coupon) {
    }

    public record CouponRedeem(@NotBlank @jakarta.validation.constraints.Size(max = 30) String code) {
    }

    public record PlanRequest(@NotBlank String planCode,
                              @Schema(description = "MONTHLY (default) or YEARLY") Subscription.Cycle cycle) {
    }

    public record AddonRequest(@NotBlank String code, @Min(1) @Max(50) int quantity) {
    }

    public record DemoConfirm(@Schema(description = "true = Pay (demo), false = Fail payment (demo)") boolean succeed) {
    }

    public record KeepResponse(@Schema(description = "For the plan that applies next; null = unlimited") Integer branchLimit,
                               Integer memberLimit, List<PlanLimits.BranchChoice> branches, List<PlanLimits.MemberChoice> members) {
    }

    public record KeepRequest(@NotNull List<Long> branchIds, @NotNull List<Long> memberIds) {
    }

    private final SubscriptionRepository subscriptions;
    private final ActivityService activity;
    private final PlanRepository plans;
    private final CouponService coupons;
    private final com.modernstock.common.RateLimiter limits;
    private final BillingInvoiceRepository invoices;
    private final BillingPaymentRepository payments;
    private final AddonRepository addons;
    private final SubscriptionAddonRepository subscriptionAddons;
    private final EntitlementService entitlements;
    private final PlanLimits planLimits;
    private final BillingService billing;
    private final FileStorage files;

    @Operation(summary = "Plan, status, usage, add-ons and the plans to choose",
            description = "effectivePlanCode is what the limits come from: PRO during the trial, FREE after a plan ends. Limits "
                    + "include add-ons; null = unlimited.")
    @GetMapping
    @Transactional(readOnly = true)
    public BillingResponse getBilling() {
        Long businessId = BusinessContext.businessId();
        Subscription s = subscriptions.findByBusinessId(businessId).orElseThrow();
        Plan plan = plans.findById(s.getPlanId()).orElseThrow();
        Plan effective = entitlements.effectivePlan(businessId);
        List<Usage> usage = effective.getLimits().stream()
                .map(l -> new Usage(l.getLimitKey(), entitlements.limit(businessId, effective, l.getLimitKey()),
                        entitlements.usage(businessId, l.getLimitKey())))
                .toList();
        List<InvoiceView> open = invoices.findByBusinessIdAndStatus(businessId, BillingInvoice.Status.OPEN).stream()
                .map(this::view).toList();
        Map<Long, Addon> catalog = new LinkedHashMap<>();
        addons.findAllByOrderBySortOrder().forEach(a -> catalog.put(a.getId(), a));
        List<MyAddon> mine = subscriptionAddons.findByBusinessIdAndStatusInOrderById(businessId,
                        List.of(SubscriptionAddon.Status.PENDING, SubscriptionAddon.Status.ACTIVE)).stream()
                .map(sa -> {
                    Addon a = catalog.get(sa.getAddonId());
                    return new MyAddon(sa.getId(), a.getCode(), a.getName(), a.getNameKm(), a.getLimitKey(), sa.getQuantity(), sa.getStatus(),
                            sa.isRemoveAtPeriodEnd(), a.price(s.getBillingCycle()).multiply(BigDecimal.valueOf(sa.getQuantity())));
                }).toList();
        return new BillingResponse(plan.getCode(), plan.getName(), effective.getCode(), s.getStatus(), s.getBillingCycle(),
                s.getPendingBillingCycle(), s.getTrialEndsAt(), s.getCurrentPeriodEnd(), s.isCancelAtPeriodEnd(), usage, s.isSubscribed(),
                s.getPendingPlanId() == null ? null : plans.findById(s.getPendingPlanId()).map(Plan::getCode).orElse(null),
                s.getPastDueSince() == null ? null : s.getPastDueSince().plus(BillingService.GRACE), billing.demo(),
                open.isEmpty() ? null : open.get(0), open,
                plans.findByPublicPlanTrueOrderBySortOrder().stream().map(BillingController::planView).toList(),
                catalog.values().stream().filter(Addon::isActive).map(BillingController::addonView).toList(), mine,
                BillingService.BANKS, billing.transferAccounts(), billing.company(), coupons.running(businessId).orElse(null));
    }

    @Operation(summary = "Enter a coupon",
            description = "FR-10.16: its discount goes on the next full-period invoices (an open first or renewal invoice gets it now). "
                    + "422 COUPON_INVALID, COUPON_USED (this business had it), COUPON_RUNNING (another one runs).")
    @PostMapping("/coupon")
    public BillingResponse redeemCoupon(@Valid @RequestBody CouponRedeem req) {
        limits.check(com.modernstock.common.RateLimiter.Rule.COUPON_BUSINESS, String.valueOf(BusinessContext.businessId()));
        CouponService.Running r = billing.redeemCoupon(req.code());
        activity.record("COUPON_REDEEMED", "SUBSCRIPTION", null, r.code(), null, null,
                ActivityService.values("coupon", r.code(), "percentOff", r.percentOff(), "periods", r.periodsLeft()));
        return getBilling();
    }

    @Operation(summary = "Billing invoices (SUB-…)", description = "Newest first.")
    @GetMapping("/invoices")
    @Transactional(readOnly = true)
    public List<InvoiceView> billingInvoices() {
        return invoices.findByBusinessIdOrderByCreatedAtDesc(BusinessContext.businessId()).stream().map(this::view).toList();
    }

    @Operation(summary = "Subscribe to a paid plan",
            description = "Monthly or yearly (10 months' price). In the trial: chosen, billed when the trial ends. After it: an "
                    + "invoice for the first period, active once paid.")
    @PostMapping("/subscribe")
    public BillingResponse subscribe(@Valid @RequestBody PlanRequest req) {
        billing.subscribe(req.planCode(), req.cycle());
        activity.record("SUBSCRIBED", "SUBSCRIPTION", null, req.planCode(), null, null,
                ActivityService.values("plan", req.planCode(), "cycle", req.cycle() == null ? null : req.cycle().name()));
        return getBilling();
    }

    @Operation(summary = "Change plan or cycle",
            description = "Upgrade: an invoice for the price difference over the days left (FR-10.17); the plan changes once paid. "
                    + "Downgrade (FREE = cancel) and a new cycle: at the end of the period; 422 BILLING_DOWNGRADE_OVER_LIMIT while "
                    + "usage is above the new limits.")
    @PostMapping("/change-plan")
    public BillingResponse changePlan(@Valid @RequestBody PlanRequest req) {
        billing.changePlan(req.planCode(), req.cycle());
        activity.record("PLAN_CHANGED", "SUBSCRIPTION", null, req.planCode(), null, null,
                ActivityService.values("plan", req.planCode(), "cycle", req.cycle() == null ? null : req.cycle().name()));
        return getBilling();
    }

    @Operation(summary = "Cancel", description = "Renewal stops; the plan stays until the period ends.")
    @PostMapping("/cancel")
    public BillingResponse cancelSubscription() {
        billing.cancel();
        activity.record("SUBSCRIPTION_CANCELLED", "SUBSCRIPTION", null, null, null, null, null);
        return getBilling();
    }

    @Operation(summary = "Keep the plan after a cancel")
    @PostMapping("/resume")
    public BillingResponse resumeSubscription() {
        billing.resume();
        activity.record("SUBSCRIPTION_RESUMED", "SUBSCRIPTION", null, null, null, null, null);
        return getBilling();
    }

    @Operation(summary = "Add an add-on", description = "FR-10.6: an invoice for the days left of the period; it works once paid "
            + "and renews with the plan. Only on an active paid plan.")
    @PostMapping("/addons")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingResponse addAddon(@Valid @RequestBody AddonRequest req) {
        SubscriptionAddon added = billing.addAddon(req.code(), req.quantity());
        activity.record("ADDON_ADDED", "SUBSCRIPTION", added.getId(), addonName(added), null, null,
                ActivityService.values("quantity", req.quantity()));
        return getBilling();
    }

    @Operation(summary = "Remove an add-on", description = "Not paid yet: gone now. Active: ends with the period, not renewed.")
    @DeleteMapping("/addons/{id}")
    public BillingResponse removeAddon(@PathVariable Long id) {
        billing.removeAddon(id);
        activity.record("ADDON_REMOVED", "SUBSCRIPTION", id, subscriptionAddons.findById(id).map(this::addonName).orElse(null), null, null, null);
        return getBilling();
    }

    private String addonName(SubscriptionAddon sa) {
        return addons.findById(sa.getAddonId()).map(Addon::getName).orElse(null);
    }

    @Operation(summary = "Keep an add-on after removing it")
    @PostMapping("/addons/{id}/keep")
    public BillingResponse keepAddon(@PathVariable Long id) {
        billing.keepAddon(id);
        return getBilling();
    }

    @Operation(summary = "Pay an open billing invoice", description = "Starts a payment through the gateway; it stays PENDING until confirmed.")
    @PostMapping("/invoices/{id}/pay")
    public PaymentView payInvoice(@PathVariable Long id) {
        return view(billing.pay(id));
    }

    @Operation(summary = "Pay by bank transfer", description = "FR-10.14: the screenshot of the transfer (ABA, ACLEDA, KHQR; form fields "
            + "\"file\" and \"bank\"). The invoice is paid when the super admin approves it.")
    @PostMapping(value = "/invoices/{id}/transfer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentView payByTransfer(@PathVariable Long id, @RequestParam("bank") String bank, @RequestParam("file") MultipartFile file) {
        return view(billing.transfer(id, bank, file));
    }

    @Operation(summary = "A bank transfer's screenshot")
    @GetMapping("/payments/{id}/proof")
    public ResponseEntity<Resource> transferProof(@PathVariable Long id) {
        BillingPayment p = payments.findById(id).filter(x -> x.getBusinessId().equals(BusinessContext.businessId()))
                .orElseThrow(() -> ApiException.notFound("billing payment"));
        return proof(p, files);
    }

    @Operation(summary = "Demo gateway confirmation",
            description = "Pay (demo) or Fail payment (demo). Only when billing.mode=demo; no money is charged.")
    @PostMapping("/payments/{id}/demo")
    public PaymentView confirmDemoPayment(@PathVariable Long id, @RequestBody DemoConfirm req) {
        return view(billing.confirmDemo(id, req.succeed()));
    }

    @Operation(summary = "What stays active when limits go down", description = "FR-10.18: the branches and members, with the limits "
            + "of the plan that applies next. The main branch and the Owners always stay.")
    @GetMapping("/keep")
    @Transactional(readOnly = true)
    public KeepResponse keepChoices() {
        Long businessId = BusinessContext.businessId();
        Plan next = nextPlan(businessId);
        return new KeepResponse(entitlements.limit(businessId, next, EntitlementService.BRANCHES),
                entitlements.limit(businessId, next, EntitlementService.MEMBERS), planLimits.branches(businessId),
                planLimits.members(businessId));
    }

    @Operation(summary = "Choose what stays active", description = "Applied now if the business is already above its limits, and "
            + "when they go down. 422 BILLING_KEEP_TOO_MANY above the next plan's limits.")
    @PutMapping("/keep")
    public KeepResponse keep(@Valid @RequestBody KeepRequest req) {
        Long businessId = BusinessContext.businessId();
        planLimits.keep(businessId, entitlements.effectivePlan(businessId), nextPlan(businessId), req.branchIds(), req.memberIds());
        return keepChoices();
    }

    /** The plan after this period: a pending downgrade, Free after a cancel, else the one in effect. */
    private Plan nextPlan(Long businessId) {
        Subscription s = subscriptions.findByBusinessId(businessId).orElseThrow();
        if (s.getPendingPlanId() != null) {
            return plans.findById(s.getPendingPlanId()).orElseThrow();
        }
        if (s.isCancelAtPeriodEnd() || (s.getStatus() == Subscription.Status.TRIALING && !s.isSubscribed())) {
            return plans.findByCode(Plan.FREE).orElseThrow();
        }
        return s.getStatus() == Subscription.Status.TRIALING ? plans.findById(s.getPlanId()).orElseThrow()
                : entitlements.effectivePlan(businessId);
    }

    /** The screenshot of a bank transfer, never cached. */
    static ResponseEntity<Resource> proof(BillingPayment p, FileStorage files) {
        Path path = p.getProofPath() == null ? null : files.resolve(p.getProofPath()).filter(Files::isRegularFile).orElse(null);
        if (path == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(FileStorage.contentType(p.getProofPath())))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }

    private InvoiceView view(BillingInvoice i) {
        Plan plan = plans.findById(i.getPlanId()).orElseThrow();
        List<BillingPayment> all = payments.findByInvoiceIdOrderByCreatedAtDesc(i.getId());
        List<BillingPayment> tries = all.stream().filter(p -> BillingService.BANK_TRANSFER.equals(p.getGateway())).toList();
        PaymentView paidWith = all.stream().filter(p -> p.getStatus() == BillingPayment.Status.SUCCEEDED).findFirst().map(this::view).orElse(null);
        boolean pending = tries.stream().anyMatch(p -> p.getStatus() == BillingPayment.Status.PENDING);
        String rejected = !pending && !tries.isEmpty() && tries.get(0).getStatus() == BillingPayment.Status.FAILED
                ? tries.get(0).getFailureReason() : null;
        return new InvoiceView(i.getId(), i.getNumber(), i.getKind(), plan.getCode(), plan.getName(), i.getBillingCycle(), i.getDescription(),
                i.getPeriodStart(), i.getPeriodEnd(), i.getAmount(), i.getStatus(), i.getCreatedAt(), i.getPaidAt(),
                i.getStatus() == BillingInvoice.Status.OPEN && pending, i.getStatus() == BillingInvoice.Status.OPEN ? rejected : null, paidWith,
                i.getDiscount(), i.getCouponCode());
    }

    private PaymentView view(BillingPayment p) {
        return new PaymentView(p.getId(), p.getInvoiceId(), p.getGateway(), p.getReference(), p.getAmount(), p.getStatus(),
                p.getFailureReason(), p.getBank());
    }

    static PlanView planView(Plan p) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        p.getLimits().forEach(l -> limits.put(l.getLimitKey(), l.getValue()));
        return new PlanView(p.getCode(), p.getName(), p.getPriceMonthly(), BillingService.price(p, Subscription.Cycle.YEARLY), limits);
    }

    static AddonView addonView(Addon a) {
        return new AddonView(a.getCode(), a.getName(), a.getNameKm(), a.getLimitKey(), a.getQuantity(), a.getPriceMonthly(), a.price(Subscription.Cycle.YEARLY));
    }
}
