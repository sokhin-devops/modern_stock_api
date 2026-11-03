package com.modernstock.sale;

import java.math.BigDecimal;

import com.modernstock.common.TenantEntity;
import com.modernstock.money.Payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Cash added to or taken from the drawer outside a sale (small change, paying a delivery man...). */
@Getter
@Setter
@Entity
@Table(name = "cash_movements")
public class CashMovement extends TenantEntity {

    public enum Direction { IN, OUT }

    @Column(nullable = false)
    private Long cashShiftId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Payment.Currency currency;
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(nullable = false)
    private String reason;
}
