package com.modernstock.business;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Links an account to a business with a role. Platform table, filtered by business_id by hand. */
@Getter
@Setter
@Entity
@Table(name = "business_members")
public class BusinessMember extends BaseEntity {

    public enum Status { ACTIVE, DISABLED, REMOVED }

    @Column(nullable = false)
    private Long businessId;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false)
    private Long roleId;
    /** Null means all branches. */
    private Long branchId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private Long invitedBy;
    @Column(nullable = false)
    private Instant joinedAt = Instant.now();
    /** Disabled because a plan ended with fewer member places; enabled again when the business subscribes. */
    @Column(nullable = false)
    private boolean disabledByPlan;
    /** With branchId: the other branches the member works in (FR-6.18). */
    @ElementCollection
    @CollectionTable(name = "member_branches", joinColumns = @JoinColumn(name = "member_id"))
    @Column(name = "branch_id")
    private Set<Long> moreBranchIds = new HashSet<>();

    /** Every branch the member works in, sorted, or null for all branches. */
    public List<Long> workBranchIds() {
        if (branchId == null) {
            return null;
        }
        Set<Long> all = new TreeSet<>(moreBranchIds);
        all.add(branchId);
        return List.copyOf(all);
    }
}
