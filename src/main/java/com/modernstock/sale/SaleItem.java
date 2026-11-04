package com.modernstock.sale;

import java.math.BigDecimal;

import com.modernstock.common.TenantLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A sold line, on a sales receipt or on a delivery (exactly one of the two).
 * cost_at_sale keeps the average cost of that moment, so gross profit never changes later.
 */
@Getter
@Setter
@Entity
@Table(name = "sale_items")
public class SaleItem extends TenantLine {

    private Long salesReceiptId;
    private Long deliveryId;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal qty;
    @Column(nullable = false)
    private BigDecimal baseQty;
    @Column(nullable = false)
    private BigDecimal unitPrice;
    @Column(nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal costAtSale;
    /** The sales order line a delivered line fills, if any. */
    private Long salesOrderItemId;
}
