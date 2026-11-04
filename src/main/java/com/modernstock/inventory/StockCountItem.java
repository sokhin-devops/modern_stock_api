package com.modernstock.inventory;

import java.math.BigDecimal;

import com.modernstock.common.TenantLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_count_items")
public class StockCountItem extends TenantLine {

    @Column(nullable = false)
    private Long productId;
    /** Base units the system had when the count started. */
    @Column(nullable = false)
    private BigDecimal systemQty;
    /** Base units counted; empty = not counted yet (left as it is). */
    private BigDecimal countedQty;
    /** Average cost when the count started, to value the differences. */
    @Column(nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;
}
