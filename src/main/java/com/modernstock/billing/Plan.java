package com.modernstock.billing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "plans")
public class Plan {

    public static final String FREE = "FREE";
    public static final String PRO = "PRO";

    @Id
    private Long id;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private BigDecimal priceMonthly;
    private BigDecimal priceYearly;
    @Column(name = "is_public", nullable = false)
    private boolean publicPlan;
    @Column(nullable = false)
    private int sortOrder;

    @OneToMany(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id")
    @OrderBy("id")
    private List<PlanLimit> limits = new ArrayList<>();

    /** The limit for a key: null = unlimited. A key the plan does not list counts as 0 (not included). */
    public Integer limit(String key) {
        PlanLimit row = limits.stream().filter(l -> l.getLimitKey().equals(key)).findFirst().orElse(null);
        return row == null ? Integer.valueOf(0) : row.getValue();
    }
}
