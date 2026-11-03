package com.modernstock.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** branches, members, products (null = unlimited); features such as telegram_alerts are 0 or 1. */
@Getter
@Entity
@Table(name = "plan_limits")
public class PlanLimit {

    @Id
    private Long id;
    @Column(nullable = false)
    private String limitKey;
    private Integer value;
}
