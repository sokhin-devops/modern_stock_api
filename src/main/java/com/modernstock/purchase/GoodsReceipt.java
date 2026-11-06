package com.modernstock.purchase;

import java.time.Instant;
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

/** Goods that arrived: stock goes up and a bill is created from it. */
@Getter
@Setter
@Entity
@Table(name = "goods_receipts")
public class GoodsReceipt extends TenantEntity {

    public enum Status { DRAFT, POSTED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long purchaseOrderId;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long warehouseId;
    @Column(nullable = false)
    private Instant receivedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private Instant postedAt;
    private String note;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    @OrderBy("id")
    private List<GoodsReceiptItem> items = new ArrayList<>();

    /** When and by whom it was voided (PLAIN.md 11.1); empty while it stands. */
    private Instant voidedAt;
    private Long voidedBy;
}
