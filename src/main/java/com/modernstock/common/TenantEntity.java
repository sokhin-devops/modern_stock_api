package com.modernstock.common;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * A row that belongs to one business. Hibernate fills business_id on insert and adds
 * "business_id = ?" to every query from the open business (PLAIN.md 11.11).
 */
@Getter
@MappedSuperclass
public abstract class TenantEntity extends BaseEntity {

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;
}
