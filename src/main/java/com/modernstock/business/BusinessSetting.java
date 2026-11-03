package com.modernstock.business;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "business_settings")
public class BusinessSetting extends TenantEntity {

    @Column(nullable = false)
    private String key;
    @Column(nullable = false)
    private String value;
}
