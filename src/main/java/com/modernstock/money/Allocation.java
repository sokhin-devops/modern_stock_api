package com.modernstock.money;

import java.math.BigDecimal;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Links money (a payment, credit note or supplier credit) to the documents it pays, in USD. */
@Getter
@Setter
@Entity
@Table(name = "allocations")
public class Allocation extends TenantEntity {

    public enum FromType { PAYMENT, CREDIT_NOTE, SUPPLIER_CREDIT }

    public enum ToType { INVOICE, BILL, SALES_RECEIPT }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FromType fromType;
    @Column(nullable = false)
    private Long fromId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ToType toType;
    @Column(nullable = false)
    private Long toId;
    @Column(nullable = false)
    private BigDecimal amount;
}
