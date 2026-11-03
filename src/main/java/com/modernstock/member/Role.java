package com.modernstock.member;

import java.util.HashSet;
import java.util.Set;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Built-in roles have no business ID; custom roles (Should) carry the business ID. */
@Getter
@Setter
@Entity
@Table(name = "roles")
public class Role extends BaseEntity {

    public static final String OWNER = "OWNER";

    private Long businessId;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String nameEn;
    private String nameKm;
    @Column(name = "is_built_in", nullable = false)
    private boolean builtIn;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    public boolean isOwner() {
        return builtIn && OWNER.equals(code);
    }
}
