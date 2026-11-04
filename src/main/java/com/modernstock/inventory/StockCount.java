package com.modernstock.inventory;

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

/**
 * A stock count (PLAIN.md 11.2): the stock of one warehouse (or one category in it) as the system knows it, what
 * was counted, and on posting the differences as COUNT_ADJUST movements.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_counts")
public class StockCount extends TenantEntity {

    public enum Status { DRAFT, POSTED, VOID }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    @Column(nullable = false)
    private Long warehouseId;
    private Long categoryId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;
    private String note;
    private Instant postedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "count_id", nullable = false)
    @OrderBy("id")
    private List<StockCountItem> items = new ArrayList<>();
}
