package com.modernstock.sale;

import java.math.BigDecimal;
import java.time.Instant;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One cashier's drawer in one branch, counted in dollars and riel separately (PLAIN.md 11.6). */
@Getter
@Setter
@Entity
@Table(name = "cash_shifts")
public class CashShift extends TenantEntity {

    public enum Status { OPEN, CLOSED }

    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long cashierId;
    @Column(nullable = false)
    private Instant openedAt;
    private Instant closedAt;
    @Column(nullable = false)
    private BigDecimal openingUsd = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal openingKhr = BigDecimal.ZERO;
    private BigDecimal expectedUsd;
    private BigDecimal expectedKhr;
    private BigDecimal countedUsd;
    private BigDecimal countedKhr;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private String note;
}
