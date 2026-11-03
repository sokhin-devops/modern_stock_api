package com.modernstock.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

/** What you plan to buy. Changes nothing until goods are received (PLAIN.md 11.7). */
@Getter
@Setter
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends TenantEntity {

    public enum Status { DRAFT, ORDERED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long supplierId;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long receiveWarehouseId;
    @Column(nullable = false)
    private LocalDate orderDate;
    private LocalDate expectedDate;
    @Column(nullable = false)
    private String currency = "USD";
    @Column(nullable = false)
    private BigDecimal exchangeRate;
    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal vat = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal total = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private String note;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    @OrderBy("id")
    private List<PurchaseOrderItem> items = new ArrayList<>();
}
