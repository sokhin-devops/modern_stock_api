package com.modernstock.inventory;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByReferenceTypeAndReferenceIdOrderByIdAsc(String referenceType, Long referenceId);

    /** The stock card: every movement of a product, newest first. */
    @Query("""
            select m from StockMovement m
            where (:productId is null or m.productId = :productId)
              and m.warehouseId in :warehouseIds
              and m.createdAt >= :from and m.createdAt < :to
            order by m.id desc
            """)
    Page<StockMovement> search(Long productId, Collection<Long> warehouseIds, Instant from, Instant to,
                               Pageable pageable);
}
