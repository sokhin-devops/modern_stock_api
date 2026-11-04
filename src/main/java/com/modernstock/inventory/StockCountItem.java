package com.modernstock.inventory;

import java.math.BigDecimal;
import java.time.Instant;

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
    @Column(nullable = false)
    private Long warehouseId;
    /** Base units the system had at snapshotAt: when the count started, or when the line was counted again. */
    @Column(nullable = false)
    private BigDecimal systemQty;
    @Column(nullable = false)
    private Instant snapshotAt;
    /** Base units counted; empty = not counted yet (left as it is). */
    private BigDecimal countedQty;
    /** Average cost when the count started, to value the differences. */
    @Column(nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;
}
