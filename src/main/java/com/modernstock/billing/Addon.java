package com.modernstock.billing;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Something a business can buy on top of its plan (FR-10.2): more of one limit, or a feature. Edited by the super admin. */
@Getter
@Setter
@Entity
@Table(name = "addons")
public class Addon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String name;
    private String nameKm;
    /** branches, members, products, or a feature key (telegram_alerts, excel_export, tax_invoice). */
    @Column(nullable = false)
    private String limitKey;
    /** Added to the limit for each one bought; a feature add-on has 1. */
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false)
    private BigDecimal priceMonthly;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
    @Column(nullable = false)
    private int sortOrder;

    /** A yearly subscription pays 10 months, like the plans (FR-10.13). */
    public BigDecimal price(Subscription.Cycle cycle) {
        return cycle == Subscription.Cycle.YEARLY ? priceMonthly.multiply(BigDecimal.TEN) : priceMonthly;
    }
}
