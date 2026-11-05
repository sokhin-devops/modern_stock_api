package com.modernstock.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.modernstock.common.TenantEntity;
import com.modernstock.money.Payment;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Goods a customer brought back, from a sales receipt or an invoice (FR-3.12). Posted at once by someone who may void
 * sales; a Cashier's return waits for approval (section 5) and moves nothing until then.
 */
@Getter
@Setter
@Entity
@Table(name = "sales_returns")
public class SalesReturn extends TenantEntity {

    public enum Status { WAITING_APPROVAL, POSTED, REJECTED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long warehouseId;
    private Long customerId;
    private Long salesReceiptId;
    private Long invoiceId;
    @Column(nullable = false)
    private Instant returnedAt;
    @Column(nullable = false)
    private BigDecimal vat = BigDecimal.ZERO;
    /** The money given back: the returned lines' share of the sale, discounts and VAT included. */
    @Column(nullable = false)
    private BigDecimal total;
    /** Damaged items at their cost when sold. */
    @Column(nullable = false)
    private BigDecimal lossValue = BigDecimal.ZERO;
    private String reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.POSTED;
    /** A requested return keeps how the customer is paid back, done when it is approved. */
    @Enumerated(EnumType.STRING)
    private Payment.Method refundMethod;
    @Enumerated(EnumType.STRING)
    private Payment.Currency refundCurrency;
    /** Who approved or rejected a requested return, and when. */
    private Long reviewedBy;
    private Instant reviewedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "return_id", nullable = false)
    @OrderBy("id")
    private List<SalesReturnItem> items = new ArrayList<>();
}
