package com.modernstock.inventory;

import java.math.BigDecimal;

import com.modernstock.common.TenantLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A product asked for, in the unit it was asked in. */
@Getter
@Setter
@Entity
@Table(name = "stock_request_items")
public class StockRequestItem extends TenantLine {

    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal qty;
    @Column(nullable = false)
    private BigDecimal baseQty;
}
