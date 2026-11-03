package com.modernstock.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.modernstock.common.TenantLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * In a draft, a line is what the user typed (product, unit, qty). On Send or Move each line is split
 * per batch picked, and keeps that batch, its expiry and the average cost, so Receive and Return
 * put back exactly the same stock at the same cost.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_transfer_items")
public class StockTransferItem extends TenantLine {

    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal qty;
    /** qty in base units; after Send this is the quantity sent. */
    @Column(nullable = false)
    private BigDecimal baseQty;
    /** Source batch: picked on purpose in a draft (for example expired goods), or by FEFO on Send. */
    private Long batchId;
    private String batchNo;
    private LocalDate expiryDate;
    private BigDecimal qtyReceived;
    private BigDecimal unitCost;
}
