package com.modernstock.catalog;

import java.math.BigDecimal;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Overrides the reorder level of a product in one branch (FR-4.7); 0 = not watched there. */
@Getter
@Setter
@Entity
@Table(name = "branch_reorder_levels")
public class BranchReorderLevel extends TenantEntity {

    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private BigDecimal reorderLevel;
}
