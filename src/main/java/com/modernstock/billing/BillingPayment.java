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

/** One try to pay a billing invoice through a gateway: Pending, then Succeeded or Failed (FR-10.8). */
@Getter
@Setter
@Entity
@Table(name = "billing_payments")
public class BillingPayment extends BaseEntity {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    @Column(nullable = false)
    private Long businessId;
    @Column(nullable = false)
    private Long invoiceId;
    @Column(nullable = false)
    private String gateway;
    @Column(nullable = false)
    private String reference;
    @Column(nullable = false)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;
    private String failureReason;
    private Instant completedAt;
}
