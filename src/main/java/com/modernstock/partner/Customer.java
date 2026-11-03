package com.modernstock.partner;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Found by phone at the POS. Pay later needs a named customer with a phone number. */
@Getter
@Setter
@Entity
@Table(name = "customers")
public class Customer extends TenantEntity {

    public enum Type { INDIVIDUAL, COMPANY }

    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type = Type.INDIVIDUAL;
    private String phone;
    private String address;
    private String vatTin;
    /** 0 = no limit set. */
    @Column(nullable = false)
    private BigDecimal creditLimit = BigDecimal.ZERO;
    @Column(nullable = false)
    private int paymentTermsDays;
    @Column(nullable = false)
    private BigDecimal openingBalance = BigDecimal.ZERO;
    private LocalDate openingBalanceDate;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
