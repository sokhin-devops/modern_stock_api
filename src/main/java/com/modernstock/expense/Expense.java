package com.modernstock.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.modernstock.common.TenantEntity;
import com.modernstock.money.Payment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Money paid out that is not a purchase of goods (PLAIN.md 11.9): rent, electricity, transport, salaries. */
@Getter
@Setter
@Entity
@Table(name = "expenses")
public class Expense extends TenantEntity {

    public enum Status { POSTED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long categoryId;
    private Long supplierId;
    @Column(nullable = false)
    private LocalDate expenseDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Payment.Method method;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Payment.Currency currency;
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(nullable = false)
    private BigDecimal exchangeRate;
    @Column(nullable = false)
    private BigDecimal amountUsd;
    private String reference;
    private String note;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.POSTED;
    private Instant voidedAt;
}
