package com.modernstock.expense;

import com.modernstock.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** What an expense is for: rent, electricity, salary... (Settings: Expense categories). */
@Getter
@Setter
@Entity
@Table(name = "expense_categories")
public class ExpenseCategory extends TenantEntity {

    @Column(nullable = false)
    private String nameEn;
    private String nameKm;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
