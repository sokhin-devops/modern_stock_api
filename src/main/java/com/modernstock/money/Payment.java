package com.modernstock.money;

import java.math.BigDecimal;
import java.time.Instant;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Money in (payment received) or out (supplier payment). Each currency is its own payment, so a customer
 * paying $10 + ៛10,000 makes two rows. Money not allocated to a document is a deposit or an advance.
 */
@Getter
@Setter
@Entity
@Table(name = "payments")
public class Payment extends TenantEntity {

    public enum Direction { IN, OUT }

    public enum PartyType { CUSTOMER, SUPPLIER, WALK_IN }

    public enum Method { CASH, KHQR, BANK }

    public enum Currency { USD, KHR }

    public enum Status { POSTED, VOID }

    @Column(nullable = false)
    private String number;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartyType partyType;
    private Long partyId;
    @Column(nullable = false)
    private Long branchId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Method method;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;
    /** In the payment's currency: USD with cents, KHR whole riel. */
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(nullable = false)
    private BigDecimal exchangeRate;
    @Column(nullable = false)
    private BigDecimal amountUsd;
    private String reference;
    private Long cashShiftId;
    @Column(nullable = false)
    private Instant paidAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.POSTED;
    private String note;
}
