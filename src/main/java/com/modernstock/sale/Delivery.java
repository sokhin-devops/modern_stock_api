package com.modernstock.sale;

import java.time.Instant;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Goods handed to a customer: stock down. Its invoice is created from it (PLAIN.md 11.8). */
@Getter
@Setter
@Entity
@Table(name = "deliveries")
public class Delivery extends TenantEntity {

    public enum Status { DRAFT, POSTED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long warehouseId;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private Instant deliveredAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private Instant postedAt;
    private String note;
    /** The sales order this delivery fills, if any. */
    private Long salesOrderId;

    /** When and by whom it was voided (PLAIN.md 11.1); empty while it stands. */
    private Instant voidedAt;
    private Long voidedBy;
}
