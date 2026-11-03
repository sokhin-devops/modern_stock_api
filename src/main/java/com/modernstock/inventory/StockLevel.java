package com.modernstock.inventory;

import java.math.BigDecimal;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Fast cache of the ledger: quantity on hand per warehouse and product, in base units. */
@Getter
@Setter
@Entity
@Table(name = "stock_levels")
public class StockLevel extends TenantEntity {

    @Column(nullable = false)
    private Long warehouseId;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private BigDecimal qtyOnHand = BigDecimal.ZERO;
}
