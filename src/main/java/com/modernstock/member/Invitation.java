package com.modernstock.member;

import java.time.Instant;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One-time link, valid 7 days. Platform table: the invited person is not in the business yet. */
@Getter
@Setter
@Entity
@Table(name = "invitations")
public class Invitation extends BaseEntity {

    public enum Method { LINK, EMAIL }

    public enum Status { PENDING, ACCEPTED, DECLINED, EXPIRED, CANCELLED }

    @Column(nullable = false)
    private Long businessId;
    @Column(nullable = false)
    private Long roleId;
    private Long branchId;
    private String email;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Method method;
    @Column(nullable = false)
    private String tokenHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(nullable = false)
    private Instant expiresAt;
    private Long invitedBy;
    private Long acceptedBy;

    public boolean isOpen() {
        return status == Status.PENDING && expiresAt.isAfter(Instant.now());
    }
}
