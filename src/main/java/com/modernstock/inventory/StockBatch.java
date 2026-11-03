package com.modernstock.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A lot of one product in one warehouse. Stock leaves by earliest expiry first (FEFO), then oldest first. */
@Getter
@Setter
@Entity
@Table(name = "stock_batches")
public class StockBatch extends TenantEntity {

    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long warehouseId;
    private String batchNo;
    private LocalDate expiryDate;
    @Column(nullable = false)
    private BigDecimal qtyOnHand = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(nullable = false)
    private Instant receivedAt = Instant.now();
}
