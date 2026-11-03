package com.modernstock.billing;

import java.time.Instant;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BusinessContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Billing")
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    public record Usage(String key, Integer limit, long used) {
    }

    public record BillingResponse(String planCode, String planName, String effectivePlanCode, Subscription.Status status,
                                  Subscription.Cycle billingCycle, Instant trialEndsAt, Instant currentPeriodEnd,
                                  boolean cancelAtPeriodEnd, List<Usage> usage) {
    }

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final EntitlementService entitlements;

    @Operation(summary = "Plan, status and usage",
            description = "effectivePlanCode is what the limits come from: PRO during the trial, FREE after a plan ends. "
                    + "limit null = unlimited. Subscribing, changing plan and the demo payment come next.")
    @GetMapping
    @PreAuthorize("hasAuthority('billing:manage')")
    @Transactional(readOnly = true)
    public BillingResponse getBilling() {
        Long businessId = BusinessContext.require().businessId();
        Subscription s = subscriptions.findByBusinessId(businessId).orElseThrow();
        Plan plan = plans.findById(s.getPlanId()).orElseThrow();
        Plan effective = entitlements.effectivePlan(businessId);
        List<Usage> usage = effective.getLimits().stream()
                .map(l -> new Usage(l.getLimitKey(), l.getValue(), entitlements.usage(businessId, l.getLimitKey())))
                .toList();
        return new BillingResponse(plan.getCode(), plan.getName(), effective.getCode(), s.getStatus(),
                s.getBillingCycle(), s.getTrialEndsAt(), s.getCurrentPeriodEnd(), s.isCancelAtPeriodEnd(), usage);
    }
}
