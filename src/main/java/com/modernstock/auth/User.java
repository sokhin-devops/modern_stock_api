package com.modernstock.auth;

import java.time.Instant;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One account per person. An account can belong to several businesses (see business_members). */
@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public enum Status { UNVERIFIED, ACTIVE, DISABLED }

    @Column(nullable = false)
    private String fullName;
    private String username;
    private String email;
    private String phone;
    @Column(nullable = false)
    private String passwordHash;
    @Column(nullable = false)
    private String locale = "km";
    @Column(nullable = false)
    private String uiMode = "system";
    @Column(nullable = false)
    private String uiDensity = "comfortable";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    private Instant emailVerifiedAt;
    /** Set when the account joined through a business's invitation without a verified email. */
    private Long managedByBusinessId;
    @Column(name = "is_super_admin", nullable = false)
    private boolean superAdmin;
    private Instant lastLoginAt;
}
