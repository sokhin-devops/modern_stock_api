package com.modernstock.inventory;

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

/** Opening stock and adjustments (damaged, expired, lost, own use, found...). */
@Getter
@Setter
@Entity
@Table(name = "stock_adjustments")
public class StockAdjustment extends TenantEntity {

    public enum Kind { OPENING, ADJUSTMENT }

    public enum Reason { OPENING, DAMAGED, EXPIRED, LOST, OWN_USE, FOUND, LOST_IN_TRANSIT, CORRECTION, OTHER }

    public enum Status { DRAFT, WAITING_APPROVAL, POSTED, REJECTED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long warehouseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reason reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private Long approvedBy;
    private Instant postedAt;
    private Instant voidedAt;
    private Long voidedBy;
    private String note;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "adjustment_id", nullable = false)
    @OrderBy("id")
    private List<StockAdjustmentItem> items = new ArrayList<>();
}
