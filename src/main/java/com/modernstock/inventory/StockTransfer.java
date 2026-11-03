package com.modernstock.inventory;

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

/**
 * Move: two warehouses of one branch, Draft → Moved in one step.
 * Branch transfer: Draft → In transit (on Send) → Received, or Returned to the sender (PLAIN.md 11.9).
 */
@Getter
@Setter
@Entity
@Table(name = "stock_transfers")
public class StockTransfer extends TenantEntity {

    public enum Kind { MOVE, BRANCH }

    public enum Status { DRAFT, MOVED, IN_TRANSIT, RECEIVED, RETURNED, VOID }

    @Column(nullable = false)
    private String number;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;
    @Column(nullable = false)
    private Long fromWarehouseId;
    @Column(nullable = false)
    private Long toWarehouseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private Instant sentAt;
    private Long sentBy;
    private Instant receivedAt;
    private Long receivedBy;
    private Instant returnedAt;
    private Long returnedBy;
    private String returnReason;
    private String carrierNote;
    /** The automatic "lost in transit" adjustment, when less arrived than was sent. */
    private Long adjustmentId;
    private String note;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "transfer_id", nullable = false)
    @OrderBy("id")
    private List<StockTransferItem> items = new ArrayList<>();

    public BigDecimal value() {
        return items.stream()
                .map(i -> i.getUnitCost() == null ? BigDecimal.ZERO : i.getUnitCost().multiply(i.getBaseQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
