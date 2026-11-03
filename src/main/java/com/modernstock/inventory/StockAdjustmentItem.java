package com.modernstock.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.modernstock.common.TenantLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_adjustment_items")
public class StockAdjustmentItem extends TenantLine {

    @Column(nullable = false)
    private Long productId;
    private Long batchId;
    private String batchNo;
    private LocalDate expiryDate;
    /** + adds stock, − removes it, in base units. */
    @Column(nullable = false)
    private BigDecimal qtyChange;
    /** Opening stock: the cost entered. Other adjustments: average cost, so losses have a value. */
    @Column(nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;
}
