package com.modernstock.money;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface PaymentRepository extends TenantRepository<Payment> {

    @Query("""
            select p from Payment p
            where p.direction = :direction and p.branchId in :branchIds
              and (:partyId is null or p.partyId = :partyId)
            order by p.id desc
            """)
    Page<Payment> search(Payment.Direction direction, Collection<Long> branchIds, Long partyId, Pageable pageable);

    List<Payment> findByCashShiftIdAndStatus(Long cashShiftId, Payment.Status status);

    @Override
    default String entityName() {
        return "Payment";
    }
}
