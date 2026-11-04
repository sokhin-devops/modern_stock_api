package com.modernstock.sale;

import java.math.BigDecimal;

import com.modernstock.common.TenantLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sales_order_items")
public class SalesOrderItem extends TenantLine {

    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private BigDecimal qtyOrdered;
    @Column(nullable = false)
    private BigDecimal qtyDelivered = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal unitPrice;
    /** For the whole ordered quantity; each delivery takes its share. */
    @Column(nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;

    public BigDecimal toDeliver() {
        return qtyOrdered.subtract(qtyDelivered).max(BigDecimal.ZERO);
    }
}
