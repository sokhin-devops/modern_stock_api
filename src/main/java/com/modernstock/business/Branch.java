package com.modernstock.business;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A shop or site. Receipts print its address and phone. */
@Getter
@Setter
@Entity
@Table(name = "branches")
public class Branch extends TenantEntity {

    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String nameEn;
    private String nameKm;
    private String address;
    private String phone;
    private String province;
    @Column(name = "is_main", nullable = false)
    private boolean main;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
