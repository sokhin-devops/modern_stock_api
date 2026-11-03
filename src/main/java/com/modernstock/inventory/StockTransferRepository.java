package com.modernstock.inventory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

import jakarta.persistence.LockModeType;

public interface StockTransferRepository extends TenantRepository<StockTransfer> {

    /** Receive and Return lock the transfer row, so it can't be received twice or received and returned at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StockTransfer t where t.id = :id")
    Optional<StockTransfer> lockById(Long id);

    /** A transfer shows in the lists of both branches. */
    @Query("""
            select t from StockTransfer t
            where (t.fromWarehouseId in :warehouseIds or t.toWarehouseId in :warehouseIds)
              and (:status is null or t.status = :status)
            order by t.id desc
            """)
    Page<StockTransfer> search(Collection<Long> warehouseIds, StockTransfer.Status status, Pageable pageable);

    /** Stock in transit belongs to neither branch but still counts in the business's stock value. */
    @Query("""
            select coalesce(sum(i.baseQty), 0) from StockTransfer t join t.items i
            where t.status = com.modernstock.inventory.StockTransfer.Status.IN_TRANSIT and i.productId = :productId
            """)
    BigDecimal inTransitQty(Long productId);

    @Query("""
            select coalesce(sum(i.baseQty * i.unitCost), 0) from StockTransfer t join t.items i
            where t.status = com.modernstock.inventory.StockTransfer.Status.IN_TRANSIT
            """)
    BigDecimal inTransitValue();

    @Override
    default String entityName() {
        return "Transfer";
    }
}
