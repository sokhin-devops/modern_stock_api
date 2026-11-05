package com.modernstock.purchase;

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

/** What the business owes a supplier for one goods receipt. Created automatically; overdue is calculated. */
@Getter
@Setter
@Entity
@Table(name = "bills")
public class Bill extends TenantEntity {

    public enum Status { UNPAID, PARTLY_PAID, PAID, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long supplierId;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long goodsReceiptId;
    private String supplierInvoiceNo;
    @Column(nullable = false)
    private LocalDate billDate;
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
    private BigDecimal paidAmount = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private String note;
    /** The supplier's paper invoice (FR-2.14), in the private documents folder. */
    private String photoPath;

    public BigDecimal open() {
        return total.subtract(paidAmount);
    }

    public void pay(BigDecimal amount) {
        paidAmount = paidAmount.add(amount);
        status = paidAmount.compareTo(total) >= 0 ? Status.PAID
                : paidAmount.signum() > 0 ? Status.PARTLY_PAID : Status.UNPAID;
    }
}
