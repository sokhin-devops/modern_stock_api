package com.modernstock.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.modernstock.common.TenantLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One line sent back, from the batch it came in with. */
@Getter
@Setter
@Entity
@Table(name = "purchase_return_items")
public class PurchaseReturnItem extends TenantLine {

    @Column(nullable = false)
    private Long goodsReceiptItemId;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal qty;
    @Column(nullable = false)
    private BigDecimal baseQty;
    /** Cost of one unitId as received. */
    @Column(nullable = false)
    private BigDecimal unitCost;
    @Column(nullable = false)
    private BigDecimal amount;
    private String batchNo;
    private LocalDate expiryDate;
}
