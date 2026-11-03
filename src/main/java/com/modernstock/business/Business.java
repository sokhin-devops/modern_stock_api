package com.modernstock.business;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** The tenant. Platform table: it is not filtered by @TenantId. */
@Getter
@Setter
@Entity
@Table(name = "businesses")
public class Business extends BaseEntity {

    public enum Status { SETTING_UP, ACTIVE, SUSPENDED, ARCHIVED }

    @Column(nullable = false)
    private String nameEn;
    private String nameKm;
    @Column(nullable = false)
    private String businessType;
    private String phone;
    private String email;
    private String address;
    private String province;
    private String vatTin;
    private String logoPath;
    private String themeColor;
    @Column(nullable = false)
    private String language = "km";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(nullable = false)
    private boolean onboardingCompleted;
}
