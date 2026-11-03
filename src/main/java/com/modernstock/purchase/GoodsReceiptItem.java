package com.modernstock.purchase;

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
@Table(name = "goods_receipt_items")
public class GoodsReceiptItem extends TenantLine {

    @Column(nullable = false)
    private Long purchaseOrderItemId;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal qty;
    @Column(nullable = false)
    private BigDecimal baseQty;
    /** Confirmed cost of one unitId; the bill uses it, so the bill always matches the stock cost. */
    @Column(nullable = false)
    private BigDecimal unitCost;
    private String batchNo;
    private LocalDate expiryDate;
}
