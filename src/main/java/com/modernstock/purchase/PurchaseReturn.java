package com.modernstock.purchase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Goods sent back to the supplier, from a goods receipt (FR-2.7). */
@Getter
@Setter
@Entity
@Table(name = "purchase_returns")
public class PurchaseReturn extends TenantEntity {

    public enum Status { WAITING_APPROVAL, POSTED, REJECTED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long warehouseId;
    @Column(nullable = false)
    private Long supplierId;
    @Column(nullable = false)
    private Long goodsReceiptId;
    @Column(nullable = false)
    private Instant returnedAt;
    /** The returned lines at the price they were received at. */
    @Column(nullable = false)
    private BigDecimal total;
    private String reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.POSTED;
    /** Who approved or rejected a prepared return, and when. */
    private Long reviewedBy;
    private Instant reviewedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "return_id", nullable = false)
    @OrderBy("id")
    private List<PurchaseReturnItem> items = new ArrayList<>();

    /** When and by whom it was voided (PLAIN.md 11.1); empty while it stands. */
    private Instant voidedAt;
    private Long voidedBy;
}
