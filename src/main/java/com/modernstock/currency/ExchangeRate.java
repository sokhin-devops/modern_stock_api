package com.modernstock.currency;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One rate per business per day. Every document saves the rate it used, so old documents never change. */
@Getter
@Setter
@Entity
@Table(name = "exchange_rates")
public class ExchangeRate extends TenantEntity {

    @Column(nullable = false)
    private LocalDate rateDate;
    @Column(nullable = false)
    private BigDecimal usdToKhr;
    @Column(nullable = false)
    private String source = "MANUAL";
}
