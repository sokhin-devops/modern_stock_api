package com.modernstock.sale;

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

/** What a customer ordered (FR-3.11). Changes nothing until it is delivered; each delivery makes its invoice. */
@Getter
@Setter
@Entity
@Table(name = "sales_orders")
public class SalesOrder extends TenantEntity {

    public enum Status { CONFIRMED, PARTIALLY_DELIVERED, DELIVERED, CLOSED, CANCELLED }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long warehouseId;
    @Column(nullable = false)
    private Long customerId;
    private Long quotationId;
    @Column(nullable = false)
    private LocalDate orderDate;
    private LocalDate deliveryDate;
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
    private Status status = Status.CONFIRMED;
    private String note;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "sales_order_id", nullable = false)
    @OrderBy("id")
    private List<SalesOrderItem> items = new ArrayList<>();

    public boolean nothingDelivered() {
        return items.stream().allMatch(i -> i.getQtyDelivered().signum() == 0);
    }
}
