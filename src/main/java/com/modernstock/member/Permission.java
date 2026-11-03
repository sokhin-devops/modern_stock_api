package com.modernstock.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** Seeded by Flyway (V2__seed_roles.sql). The code is used in @PreAuthorize, for example "sale:create". */
@Getter
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    private Long id;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String area;
    @Column(nullable = false)
    private String nameEn;
    private String nameKm;
}
