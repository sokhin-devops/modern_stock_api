package com.modernstock.billing;

import java.time.Instant;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One per business. Billing tables are filtered by business_id by hand, because the super admin sees all of them. */
@Getter
@Setter
@Entity
@Table(name = "subscriptions")
public class Subscription extends BaseEntity {

    public enum Status { TRIALING, ACTIVE, PAST_DUE, EXPIRED, CANCELLED }

    public enum Cycle { MONTHLY, YEARLY }

    @Column(nullable = false)
    private Long businessId;
    @Column(nullable = false)
    private Long planId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Cycle billingCycle = Cycle.MONTHLY;
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private Instant trialEndsAt;
    @Column(nullable = false)
    private boolean cancelAtPeriodEnd;
    private Long pendingPlanId;
    /** Switches at the end of the period (FR-10.13). */
    @Enumerated(EnumType.STRING)
    private Cycle pendingBillingCycle;
    /** The Owner chose a paid plan; during the trial it is billed when the trial ends. */
    @Column(nullable = false)
    private boolean subscribed;
    /** When the unpaid renewal started; after 7 days the plan expires to Free. */
    private Instant pastDueSince;
}
