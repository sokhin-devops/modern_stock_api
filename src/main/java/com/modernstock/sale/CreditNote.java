package com.modernstock.sale;

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

/** Money the business owes a customer back (FR-3.12): used on invoices or refunded, until nothing is left. */
@Getter
@Setter
@Entity
@Table(name = "credit_notes")
public class CreditNote extends TenantEntity {

    public enum Status { OPEN, CLOSED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    private Long customerId;
    private Long salesReturnId;
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

    /** What is left to use or refund. */
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

    /** When and by whom it was voided (PLAIN.md 11.1); empty while it stands. */
    private Instant voidedAt;
    private Long voidedBy;
}
