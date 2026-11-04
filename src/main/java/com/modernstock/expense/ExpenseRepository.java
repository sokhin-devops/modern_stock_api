package com.modernstock.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface ExpenseRepository extends TenantRepository<Expense> {

    @Query("""
            select e from Expense e
            where e.branchId in :branchIds
              and e.expenseDate between :from and :to
              and (:categoryId is null or e.categoryId = :categoryId)
            order by e.expenseDate desc, e.id desc""")
    Page<Expense> search(Collection<Long> branchIds, LocalDate from, LocalDate to, Long categoryId, Pageable pageable);

    @Query("""
            select coalesce(sum(e.amountUsd), 0) from Expense e
            where e.branchId in :branchIds and e.status = com.modernstock.expense.Expense.Status.POSTED and e.expenseDate between :from and :to""")
    BigDecimal totalUsd(Collection<Long> branchIds, LocalDate from, LocalDate to);

    boolean existsByCategoryId(Long categoryId);

    @Override
    default String entityName() {
        return "Expense";
    }
}
