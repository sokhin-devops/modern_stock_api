package com.modernstock.expense;

import java.util.List;

import com.modernstock.common.TenantRepository;

public interface ExpenseCategoryRepository extends TenantRepository<ExpenseCategory> {

    List<ExpenseCategory> findAllByOrderByNameEnAsc();

    @Override
    default String entityName() {
        return "Expense category";
    }
}
