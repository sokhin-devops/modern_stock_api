package com.modernstock.inventory;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface StockRequestRepository extends TenantRepository<StockRequest> {

    /** A request shows in the lists of both branches. */
    @Query("""
            select r from StockRequest r
            where (r.fromBranchId in :branchIds or r.toBranchId in :branchIds)
              and (:status is null or r.status = :status)
            order by r.id desc
            """)
    Page<StockRequest> search(Collection<Long> branchIds, StockRequest.Status status, Pageable pageable);

    Optional<StockRequest> findByTransferId(Long transferId);
}
