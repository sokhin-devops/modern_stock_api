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

/** One-time links: email verification and password reset (also the Owner's reset links). */
@Getter
@Setter
@Entity
@Table(name = "user_tokens")
public class UserToken extends BaseEntity {

    public enum Type { VERIFY_EMAIL, PASSWORD_RESET }

    @Column(nullable = false)
    private Long userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;
    @Column(nullable = false)
    private String tokenHash;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant usedAt;

    public boolean isUsable() {
        return usedAt == null && expiresAt.isAfter(Instant.now());
    }
}
