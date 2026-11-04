package com.modernstock.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.SettingsService;
import com.modernstock.common.ApiException;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.currency.CurrencyService;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Records payments and their allocations. Used by POS, payments received and supplier payments. */
@Service
@RequiredArgsConstructor
public class PaymentService {

    public record PaymentLine(
            @Schema(example = "CASH") @NotNull Payment.Method method,
            @Schema(example = "USD") @NotNull Payment.Currency currency,
            @Schema(description = "USD with cents or whole riel", example = "10") @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @Schema(description = "Bank or KHQR reference number") @Size(max = 60) String reference) {
    }

    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final DocumentNumberService numbers;
    private final SettingsService settings;

    @Transactional(propagation = Propagation.MANDATORY)
    public Payment record(Payment.Direction direction, Payment.PartyType partyType, Long partyId, Long branchId,
                          PaymentLine line, BigDecimal rate, Long cashShiftId, String note) {
        if (direction == Payment.Direction.IN && partyType != Payment.PartyType.SUPPLIER) {
            SettingsService.BusinessSettings s = settings.get();
            if ((line.method() == Payment.Method.KHQR && !s.khqrEnabled()) || (line.method() == Payment.Method.BANK && !s.bankEnabled())) {
                throw ApiException.rule("PAYMENT_METHOD_OFF");
            }
        }
        Payment p = new Payment();
        p.setNumber(numbers.next(direction == Payment.Direction.IN ? DocType.RCV : DocType.PAY));
        p.setDirection(direction);
        p.setPartyType(partyType);
        p.setPartyId(partyId);
        p.setBranchId(branchId);
        p.setMethod(line.method());
        p.setCurrency(line.currency());
        p.setAmount(line.currency() == Payment.Currency.KHR
                ? line.amount().setScale(0, RoundingMode.HALF_UP) : line.amount().setScale(2, RoundingMode.HALF_UP));
        p.setExchangeRate(rate);
        p.setAmountUsd(usd(line, rate));
        p.setReference(line.reference());
        p.setCashShiftId(line.method() == Payment.Method.CASH ? cashShiftId : null);
        p.setPaidAt(Instant.now());
        p.setNote(note);
        return payments.save(p);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void allocate(Payment payment, Allocation.ToType toType, Long toId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        Allocation a = new Allocation();
        a.setFromType(Allocation.FromType.PAYMENT);
        a.setFromId(payment.getId());
        a.setToType(toType);
        a.setToId(toId);
        a.setAmount(amount);
        allocations.save(a);
    }

    public static BigDecimal usd(PaymentLine line, BigDecimal rate) {
        return line.currency() == Payment.Currency.USD
                ? line.amount().setScale(2, RoundingMode.HALF_UP)
                : CurrencyService.toUsd(line.amount(), rate);
    }
}
