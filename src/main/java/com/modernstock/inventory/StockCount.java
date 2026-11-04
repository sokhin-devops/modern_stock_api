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
 * A stock count (PLAIN.md 11.2, FR-4.4, FR-4.9): the stock of one warehouse, or of every warehouse of a branch, as the
 * system knows it (all products, one category or a chosen list), what was counted, and on posting the differences as
 * COUNT_ADJUST movements. Staff submit it; Owner or Manager posts it.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_counts")
public class StockCount extends TenantEntity {

    public enum Status { DRAFT, SUBMITTED, POSTED, VOID }

    /** What is counted: every active product, one category, or the products chosen (one shelf). */
    public enum Scope { ALL, CATEGORY, PRODUCTS }

    @Column(nullable = false)
    private String number;
    @Column(nullable = false)
    private Long branchId;
    /** Empty = every warehouse of the branch; each line has its own. */
    private Long warehouseId;
    private Long categoryId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope = Scope.ALL;
    /** Counters don't see the system quantity until the count is submitted. */
    @Column(name = "is_blind", nullable = false)
    private boolean blind;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;
    private String note;
    private Instant submittedAt;
    private Instant postedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "count_id", nullable = false)
    @OrderBy("id")
    private List<StockCountItem> items = new ArrayList<>();
}
