package com.modernstock.catalog;

import java.math.BigDecimal;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A packaging unit: 1 unit = factor base units. Can have its own barcode and price. */
@Getter
@Setter
@Entity
@Table(name = "product_units")
public class ProductUnit extends TenantEntity {

    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal factor;
    private String barcode;
    private BigDecimal sellPrice;
}
