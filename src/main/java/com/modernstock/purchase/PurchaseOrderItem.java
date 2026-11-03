package com.modernstock.purchase;

import java.math.BigDecimal;

import com.modernstock.common.TenantLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Quantities and cost are in the line's unit (for example boxes at $12.00 a box). */
@Getter
@Setter
@Entity
@Table(name = "purchase_order_items")
public class PurchaseOrderItem extends TenantLine {

    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal qtyOrdered;
    @Column(nullable = false)
    private BigDecimal qtyReceived = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal unitCost;
}
