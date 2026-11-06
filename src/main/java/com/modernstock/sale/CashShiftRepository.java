package com.modernstock.sale;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface CashShiftRepository extends TenantRepository<CashShift> {

    Optional<CashShift> findByBranchIdAndCashierIdAndStatus(Long branchId, Long cashierId, CashShift.Status status);

    @Query("""
            select s from CashShift s
            where s.branchId in :branchIds and (:cashierId is null or s.cashierId = :cashierId)
              and (:status is null or s.status = :status) and s.openedAt >= :start and s.openedAt < :end
            order by s.id desc
            """)
    Page<CashShift> search(Collection<Long> branchIds, Long cashierId, CashShift.Status status, java.time.Instant start,
                           java.time.Instant end, Pageable pageable);

    @Override
    default String entityName() {
        return "Cash shift";
    }
}
