package com.modernstock.billing;

import java.math.BigDecimal;
import java.time.Instant;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** The platform's invoice to a business (SUB-…, USD), for a new subscription, a renewal, an upgrade or an add-on (FR-10.7). */
@Getter
@Setter
@Entity
@Table(name = "billing_invoices")
public class BillingInvoice extends BaseEntity {

    public enum Kind { NEW, RENEWAL, UPGRADE, ADDON }

    public enum Status { OPEN, PAID, VOID }

    @Column(nullable = false)
    private Long businessId;
    @Column(nullable = false)
    private String number;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;
    @Column(nullable = false)
    private Long planId;
    @Column(nullable = false)
    private Instant periodStart;
    @Column(nullable = false)
    private Instant periodEnd;
    @Column(nullable = false)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;
    private Instant paidAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Subscription.Cycle billingCycle = Subscription.Cycle.MONTHLY;
    /** An ADDON invoice: the add-on it starts. */
    private Long subscriptionAddonId;
    /** What the amount is for, e.g. "Pro, yearly + Extra branch". */
    private String description;
    /** FR-10.16: what a coupon took off this invoice, and which one. */
    private BigDecimal discount = BigDecimal.ZERO;
    private String couponCode;
}
