package com.modernstock.money;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface PaymentRepository extends TenantRepository<Payment> {

    /** One side of the money: payments received from customers, or payments to suppliers (not refunds either way). */
    @Query("""
            select p from Payment p
            where p.direction = :direction and p.partyType in :partyTypes and p.branchId in :branchIds
              and (:partyId is null or p.partyId = :partyId)
            order by p.id desc
            """)
    Page<Payment> search(Payment.Direction direction, Collection<Payment.PartyType> partyTypes, Collection<Long> branchIds,
                         Long partyId, Pageable pageable);

    /** Posted payments with money not yet used on a document (deposits, advances), oldest first. */
    @Query("""
            select p from Payment p
            where p.partyType = :partyType and p.partyId = :partyId and p.direction = :direction
              and p.status = com.modernstock.money.Payment.Status.POSTED
              and p.amountUsd > (select coalesce(sum(a.amount), 0) from Allocation a
                                 where a.fromType = com.modernstock.money.Allocation.FromType.PAYMENT and a.fromId = p.id)
            order by p.paidAt, p.id
            """)
    List<Payment> unallocated(Payment.PartyType partyType, Long partyId, Payment.Direction direction);

    List<Payment> findByCashShiftIdAndStatus(Long cashShiftId, Payment.Status status);

    @Override
    default String entityName() {
        return "Payment";
    }
}
