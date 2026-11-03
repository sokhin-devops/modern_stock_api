package com.modernstock.inventory;

import java.math.BigDecimal;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** The stock ledger: the source of truth, append-only (PLAIN.md 11.2). */
@Getter
@Setter
@Entity
@Table(name = "stock_movements")
public class StockMovement extends TenantEntity {

    public enum Type {
        OPENING, GOODS_RECEIPT, PURCHASE_RETURN, DELIVERY, SALES_RECEIPT, SALES_RETURN,
        TRANSFER_OUT, TRANSFER_IN, TRANSFER_RETURN, ADJUSTMENT, COUNT_ADJUST
    }

    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long warehouseId;
    private Long batchId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;
    /** + in, − out, in base units. */
    @Column(nullable = false)
    private BigDecimal qty;
    @Column(nullable = false)
    private BigDecimal unitCost;
    @Column(nullable = false)
    private BigDecimal balanceAfter;
    @Column(nullable = false)
    private String referenceType;
    @Column(nullable = false)
    private Long referenceId;
    private Long userId;
}
