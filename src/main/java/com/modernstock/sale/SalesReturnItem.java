package com.modernstock.sale;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.modernstock.common.TenantLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One returned line: good items go back to stock, damaged ones are a loss. */
@Getter
@Setter
@Entity
@Table(name = "sales_return_items")
public class SalesReturnItem extends TenantLine {

    public enum Condition { GOOD, DAMAGED }

    @Column(nullable = false)
    private Long saleItemId;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal qty;
    @Column(nullable = false)
    private BigDecimal baseQty;
    /** The sold line's price per unit after its discount. */
    @Column(nullable = false)
    private BigDecimal unitNet;
    @Column(nullable = false)
    private BigDecimal refund;
    @Column(nullable = false)
    private BigDecimal costAtSale;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Condition condition;
    private String batchNo;
    private LocalDate expiryDate;
}
