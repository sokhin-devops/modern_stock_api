package com.modernstock.billing;

import java.time.Instant;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** An add-on a business bought (FR-10.6): Pending until its invoice is paid, Active, then Ended. */
@Getter
@Setter
@Entity
@Table(name = "subscription_addons")
public class SubscriptionAddon extends BaseEntity {

    public enum Status { PENDING, ACTIVE, ENDED }

    @Column(nullable = false)
    private Long businessId;
    @Column(nullable = false)
    private Long addonId;
    @Column(nullable = false)
    private int quantity = 1;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;
    /** Removed by the Owner: it ends with the period and is not renewed. */
    @Column(nullable = false)
    private boolean removeAtPeriodEnd;
    private Instant startedAt;
    private Instant endedAt;
}
