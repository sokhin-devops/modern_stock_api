package com.modernstock.catalog;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "units")
public class Unit extends TenantEntity {

    @Column(nullable = false)
    private String nameEn;
    private String nameKm;
    @Column(nullable = false)
    private String symbol;
}
