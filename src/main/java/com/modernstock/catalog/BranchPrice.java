package com.modernstock.catalog;

import java.math.BigDecimal;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Overrides the default price of a product (and unit) in one branch. */
@Getter
@Setter
@Entity
@Table(name = "branch_prices")
public class BranchPrice extends TenantEntity {

    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal sellPrice;
}
