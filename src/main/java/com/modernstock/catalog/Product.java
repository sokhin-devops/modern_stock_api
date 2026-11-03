package com.modernstock.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Stock is always kept in the base unit (pieces). Packaging units say how many base units they hold,
 * for example 1 box = 24 pcs. avgCost is the weighted average cost for the whole business (PLAIN.md 11.3).
 */
@Getter
@Setter
@Entity
@Table(name = "products")
public class Product extends TenantEntity {

    @Column(nullable = false)
    private String sku;
    private String barcode;
    @Column(nullable = false)
    private String nameEn;
    private String nameKm;
    private Long categoryId;
    @Column(nullable = false)
    private Long baseUnitId;
    @Column(nullable = false)
    private BigDecimal avgCost = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal sellPrice = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal reorderLevel = BigDecimal.ZERO;
    @Column(nullable = false)
    private int leadTimeDays;
    @Column(nullable = false)
    private boolean trackExpiry;
    @Column(nullable = false)
    private boolean vatExempt;
    private String imagePath;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "product_id", nullable = false)
    @OrderBy("factor")
    private List<ProductUnit> units = new ArrayList<>();

    public Optional<ProductUnit> unit(Long unitId) {
        return units.stream().filter(u -> Objects.equals(u.getUnitId(), unitId)).findFirst();
    }
}
