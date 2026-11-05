package com.modernstock.inventory;

import java.time.Instant;
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

/**
 * A transfer request (FR-4.11): one branch asks another for goods. Open → Fulfilled (the other branch made the
 * transfer, which then goes its own way: sent, received), Declined (with a reason), or Cancelled by the branch that asked.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_requests")
public class StockRequest extends TenantEntity {

    public enum Status { OPEN, FULFILLED, DECLINED, CANCELLED }

    @Column(nullable = false)
    private String number;
    /** The branch asked for the goods. */
    @Column(nullable = false)
    private Long fromBranchId;
    /** The branch that asks and receives. */
    @Column(nullable = false)
    private Long toBranchId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private LocalDate neededBy;
    private String note;
    private Long requestedBy;
    private Long decidedBy;
    private Instant decidedAt;
    private String declineReason;
    private Long transferId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "request_id", nullable = false)
    @OrderBy("id")
    private List<StockRequestItem> items = new ArrayList<>();
}
