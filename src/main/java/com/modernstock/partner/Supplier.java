package com.modernstock.partner;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "suppliers")
public class Supplier extends TenantEntity {

    @Column(nullable = false)
    private String name;
    private String contactPerson;
    private String phone;
    private String telegram;
    private String address;
    private String vatTin;
    @Column(nullable = false)
    private int paymentTermsDays;
    /** What the business already owed this supplier when it started using the system. */
    @Column(nullable = false)
    private BigDecimal openingBalance = BigDecimal.ZERO;
    private LocalDate openingBalanceDate;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
