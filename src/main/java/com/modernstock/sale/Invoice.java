package com.modernstock.sale;

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

/** What a customer owes for one delivery. Created automatically from the delivery. */
@Getter
@Setter
@Entity
@Table(name = "invoices")
public class Invoice extends TenantEntity {

    public enum Type { STANDARD, TAX, DEBIT_NOTE }

    public enum Status { UNPAID, PARTLY_PAID, PAID, VOID }

    @Column(nullable = false)
    private String number;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type = Type.STANDARD;
    @Column(nullable = false)
    private Long branchId;
    private Long deliveryId;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private LocalDate invoiceDate;
    @Column(nullable = false)
    private LocalDate dueDate;
    @Column(nullable = false)
    private String currency = "USD";
    @Column(nullable = false)
    private BigDecimal exchangeRate;
    @Column(nullable = false)
    private BigDecimal subtotal;
    @Column(nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal vat = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal total;
    @Column(nullable = false)
    private BigDecimal totalKhr;
    @Column(nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private String note;
    /** A debit note: the invoice it adds to (FR-3.19). */
    private Long relatedInvoiceId;

    public BigDecimal open() {
        return total.subtract(paidAmount);
    }

    public void pay(BigDecimal amount) {
        paidAmount = paidAmount.add(amount);
        status = paidAmount.compareTo(total) >= 0 ? Status.PAID
                : paidAmount.signum() > 0 ? Status.PARTLY_PAID : Status.UNPAID;
    }
}
