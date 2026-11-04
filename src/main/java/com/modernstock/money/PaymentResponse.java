package com.modernstock.money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record PaymentResponse(Long id, String number, Payment.Direction direction, Payment.PartyType partyType,
                              Long partyId, @Schema(description = "Customer or supplier name; empty for walk-in") String partyName,
                              Long branchId, Payment.Method method, Payment.Currency currency,
                              BigDecimal amount, BigDecimal exchangeRate, BigDecimal amountUsd,
                              @Schema(description = "Left over as a deposit (customer) or advance (supplier)")
                              BigDecimal unallocatedUsd,
                              String reference, Instant paidAt, Payment.Status status,
                              List<AllocationLine> allocations) {

    public record AllocationLine(Allocation.ToType toType, Long toId, BigDecimal amount) {
    }

    public static PaymentResponse from(Payment p, List<Allocation> allocations) {
        return from(p, allocations, null);
    }

    public static PaymentResponse from(Payment p, List<Allocation> allocations, String partyName) {
        BigDecimal allocated = allocations.stream().map(Allocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaymentResponse(p.getId(), p.getNumber(), p.getDirection(), p.getPartyType(), p.getPartyId(), partyName,
                p.getBranchId(), p.getMethod(), p.getCurrency(), p.getAmount(), p.getExchangeRate(), p.getAmountUsd(),
                p.getAmountUsd().subtract(allocated).max(BigDecimal.ZERO), p.getReference(), p.getPaidAt(),
                p.getStatus(),
                allocations.stream().map(a -> new AllocationLine(a.getToType(), a.getToId(), a.getAmount())).toList());
    }
}
