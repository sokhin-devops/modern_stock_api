package com.modernstock.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.modernstock.common.Dates;
import com.modernstock.common.TenantEntity;

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

/** A price offer to a customer, valid until a date (FR-3.10). Changes nothing until it becomes a sales order. */
@Getter
@Setter
@Entity
@Table(name = "quotations")
public class Quotation extends TenantEntity {

    public enum Status { DRAFT, SENT, ACCEPTED, DECLINED, EXPIRED }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private LocalDate quoteDate;
    @Column(nullable = false)
    private LocalDate validUntil;
    @Column(nullable = false)
    private BigDecimal exchangeRate;
    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal orderDiscount = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal vat = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal total = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;
    private Long salesOrderId;
    private String note;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "quotation_id", nullable = false)
    @OrderBy("id")
    private List<QuotationItem> items = new ArrayList<>();

    /** A draft or sent offer whose date has passed is expired, even before the hourly job marks it. */
    public Status effectiveStatus() {
        return (status == Status.DRAFT || status == Status.SENT) && validUntil.isBefore(Dates.today()) ? Status.EXPIRED : status;
    }
}
