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

/** A counter sale paid in full: stock down, no debt. */
@Getter
@Setter
@Entity
@Table(name = "sales_receipts")
public class SalesReceipt extends TenantEntity {

    public enum Status { POSTED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long warehouseId;
    private Long cashShiftId;
    @Column(nullable = false)
    private Long cashierId;
    private Long customerId;
    @Column(nullable = false)
    private Instant soldAt;
    @Column(nullable = false)
    private String currency = "USD";
    @Column(nullable = false)
    private BigDecimal exchangeRate;
    @Column(nullable = false)
    private BigDecimal subtotal;
    @Column(nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal vat = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal total;
    @Column(nullable = false)
    private BigDecimal totalKhr;
    @Column(nullable = false)
    private BigDecimal changeUsd = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal changeKhr = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private Instant postedAt;
    private Instant voidedAt;
    private Long voidedBy;
    private String note;
}
