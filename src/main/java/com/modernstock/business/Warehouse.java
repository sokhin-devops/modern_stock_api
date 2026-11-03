package com.modernstock.business;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Where stock is kept inside a branch (Main, Shop floor, Back store). Every branch has at least one,
 * with exactly one sales warehouse (the POS sells from it) and one receiving warehouse (goods arrive there).
 */
@Getter
@Setter
@Entity
@Table(name = "warehouses")
public class Warehouse extends TenantEntity {

    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private String nameEn;
    private String nameKm;
    @Column(name = "is_sales", nullable = false)
    private boolean sales;
    @Column(name = "is_receiving", nullable = false)
    private boolean receiving;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
