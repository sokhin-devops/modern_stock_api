package com.modernstock.purchase;

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

/** Money a supplier owes the business back (FR-2.8): used on bills or refunded, until nothing is left. */
@Getter
@Setter
@Entity
@Table(name = "supplier_credits")
public class SupplierCredit extends TenantEntity {

    public enum Status { OPEN, CLOSED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long supplierId;
    private Long purchaseReturnId;
    @Column(nullable = false)
    private Instant issuedAt;
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(nullable = false)
    private BigDecimal usedAmount = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal refundedAmount = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;
    private String reason;

    /** What is left to use or get back. */
    public BigDecimal left() {
        return amount.subtract(usedAmount).subtract(refundedAmount);
    }

    public void use(BigDecimal value) {
        usedAmount = usedAmount.add(value);
        close();
    }

    public void refund(BigDecimal value) {
        refundedAmount = refundedAmount.add(value);
        close();
    }

    private void close() {
        if (left().signum() <= 0) {
            status = Status.CLOSED;
        }
    }
}
