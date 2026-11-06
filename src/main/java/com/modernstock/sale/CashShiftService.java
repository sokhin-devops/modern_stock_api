package com.modernstock.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.auth.UserRepository;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.common.PageResponse;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentRepository;
import com.modernstock.notification.TelegramAlerts;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Expected cash in each currency = opening cash + cash payments received in that currency
 * − change given in that currency + cash added − cash taken out (PLAIN.md 11.6).
 */
@Service
@RequiredArgsConstructor
public class CashShiftService {

    public record OpenShiftRequest(@Schema(description = "Defaults to the selected branch") Long branchId,
                                   @DecimalMin("0") BigDecimal openingUsd,
                                   @DecimalMin("0") BigDecimal openingKhr) {
    }

    public record CloseShiftRequest(@NotNull @DecimalMin("0") BigDecimal countedUsd,
                                    @NotNull @DecimalMin("0") BigDecimal countedKhr,
                                    @Size(max = 500) String note) {
    }

    public record CashMovementRequest(@NotNull CashMovement.Direction direction,
                                      @NotNull Payment.Currency currency,
                                      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                                      @NotBlank @Size(max = 255) String reason) {
    }

    public record CashShiftResponse(Long id, Long branchId, Long cashierId, String cashierName, CashShift.Status status,
                                    Instant openedAt, Instant closedAt, BigDecimal openingUsd, BigDecimal openingKhr,
                                    BigDecimal cashReceivedUsd, BigDecimal cashReceivedKhr,
                                    BigDecimal changeGivenUsd, BigDecimal changeGivenKhr,
                                    BigDecimal cashAddedUsd, BigDecimal cashAddedKhr,
                                    BigDecimal cashTakenUsd, BigDecimal cashTakenKhr,
                                    BigDecimal expectedUsd, BigDecimal expectedKhr,
                                    BigDecimal countedUsd, BigDecimal countedKhr,
                                    @Schema(description = "counted − expected: + over, − short") BigDecimal differenceUsd,
                                    BigDecimal differenceKhr, long receipts, long voidedReceipts) {
    }

    private record Totals(BigDecimal receivedUsd, BigDecimal receivedKhr, BigDecimal changeUsd, BigDecimal changeKhr,
                          BigDecimal addedUsd, BigDecimal addedKhr, BigDecimal takenUsd, BigDecimal takenKhr) {
        BigDecimal expectedUsd(CashShift s) {
            return s.getOpeningUsd().add(receivedUsd).subtract(changeUsd).add(addedUsd).subtract(takenUsd);
        }

        BigDecimal expectedKhr(CashShift s) {
            return s.getOpeningKhr().add(receivedKhr).subtract(changeKhr).add(addedKhr).subtract(takenKhr);
        }
    }

    private final CashShiftRepository shifts;
    private final CashMovementRepository movements;
    private final PaymentRepository payments;
    private final SalesReceiptRepository receipts;
    private final UserRepository users;
    private final BranchScope scope;
    private final ApplicationEventPublisher events;
    private final ActivityService activity;

    @Transactional
    public CashShiftResponse open(OpenShiftRequest req) {
        Long branchId = BusinessContext.branchForWrite(req.branchId());
        Long me = CurrentUser.id();
        if (shifts.findByBranchIdAndCashierIdAndStatus(branchId, me, CashShift.Status.OPEN).isPresent()) {
            throw ApiException.conflict("CASH_SHIFT_ALREADY_OPEN");
        }
        CashShift s = new CashShift();
        s.setBranchId(branchId);
        s.setCashierId(me);
        s.setOpenedAt(Instant.now());
        s.setOpeningUsd(req.openingUsd() == null ? BigDecimal.ZERO : req.openingUsd());
        s.setOpeningKhr(req.openingKhr() == null ? BigDecimal.ZERO : req.openingKhr());
        s.setStatus(CashShift.Status.OPEN);
        shifts.save(s);
        return toResponse(s);
    }

    @Transactional(readOnly = true)
    public Optional<CashShift> openShiftOf(Long branchId, Long cashierId) {
        return shifts.findByBranchIdAndCashierIdAndStatus(branchId, cashierId, CashShift.Status.OPEN);
    }

    @Transactional(readOnly = true)
    public CashShiftResponse current(Long branchId) {
        Long branch = BusinessContext.branchForWrite(branchId);
        return openShiftOf(branch, CurrentUser.id()).map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("Open cash shift"));
    }

    @Transactional
    public CashShiftResponse addMovement(Long id, CashMovementRequest req) {
        CashShift s = mineOrAll(id);
        requireOpen(s);
        CashMovement m = new CashMovement();
        m.setCashShiftId(s.getId());
        m.setDirection(req.direction());
        m.setCurrency(req.currency());
        m.setAmount(req.amount());
        m.setReason(req.reason());
        movements.save(m);
        return toResponse(s);
    }

    @Transactional
    public CashShiftResponse close(Long id, CloseShiftRequest req) {
        CashShift s = mineOrAll(id);
        requireOpen(s);
        Totals t = totals(s);
        s.setExpectedUsd(t.expectedUsd(s));
        s.setExpectedKhr(t.expectedKhr(s));
        s.setCountedUsd(req.countedUsd());
        s.setCountedKhr(req.countedKhr());
        s.setNote(req.note());
        s.setClosedAt(Instant.now());
        s.setStatus(CashShift.Status.CLOSED);
        if (short_(s.getCountedUsd(), s.getExpectedUsd()) || short_(s.getCountedKhr(), s.getExpectedKhr())) {
            events.publishEvent(new TelegramAlerts.ShiftShort(BusinessContext.businessId(), s.getId()));
        }
        activity.changed("SHIFT_CLOSED", "CASH_SHIFT", s.getId(), users.findById(s.getCashierId()).map(u -> u.getFullName()).orElse(null),
                s.getBranchId(), ActivityService.values("cashUsd", s.getExpectedUsd(), "cashKhr", s.getExpectedKhr()),
                ActivityService.values("cashUsd", s.getCountedUsd(), "cashKhr", s.getCountedKhr()));
        return toResponse(s);
    }

    /** Cashiers see their own shifts; cash_shift:all sees every shift of the branches in view. */
    @Transactional(readOnly = true)
    public PageResponse<CashShiftResponse> list(CashShift.Status status, java.time.LocalDate from, java.time.LocalDate to,
                                                Pageable pageable) {
        Long cashier = BusinessContext.has("cash_shift:all") ? null : CurrentUser.id();
        java.time.Instant start = (from == null ? java.time.LocalDate.of(2000, 1, 1) : from)
                .atStartOfDay(com.modernstock.common.Dates.PHNOM_PENH).toInstant();
        java.time.Instant end = (to == null ? java.time.LocalDate.of(2999, 1, 1) : to.plusDays(1))
                .atStartOfDay(com.modernstock.common.Dates.PHNOM_PENH).toInstant();
        return PageResponse.of(shifts.search(scope.branchIds(), cashier, status, start, end, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public CashShiftResponse get(Long id) {
        return toResponse(mineOrAll(id));
    }

    private CashShift mineOrAll(Long id) {
        CashShift s = shifts.get(id);
        BusinessContext.requireBranch(s.getBranchId());
        if (!s.getCashierId().equals(CurrentUser.id()) && !BusinessContext.has("cash_shift:all")) {
            throw ApiException.forbidden("NOT_YOUR_SHIFT");
        }
        return s;
    }

    private static void requireOpen(CashShift s) {
        if (s.getStatus() != CashShift.Status.OPEN) {
            throw ApiException.rule("CASH_SHIFT_NOT_OPEN");
        }
    }

    private Totals totals(CashShift s) {
        List<Payment> posted = payments.findByCashShiftIdAndStatus(s.getId(), Payment.Status.POSTED).stream()
                .filter(p -> p.getMethod() == Payment.Method.CASH).toList();
        List<Payment> cash = posted.stream().filter(p -> p.getDirection() == Payment.Direction.IN).toList();
        // Cash refunds (credit notes) leave the drawer like cash taken out.
        List<Payment> refunds = posted.stream().filter(p -> p.getDirection() == Payment.Direction.OUT).toList();
        List<CashMovement> moves = movements.findByCashShiftIdOrderById(s.getId());
        return new Totals(
                sum(cash.stream().filter(p -> p.getCurrency() == Payment.Currency.USD).map(Payment::getAmount).toList()),
                sum(cash.stream().filter(p -> p.getCurrency() == Payment.Currency.KHR).map(Payment::getAmount).toList()),
                receipts.changeUsd(s.getId()),
                receipts.changeKhr(s.getId()),
                sum(moves, CashMovement.Direction.IN, Payment.Currency.USD),
                sum(moves, CashMovement.Direction.IN, Payment.Currency.KHR),
                sum(moves, CashMovement.Direction.OUT, Payment.Currency.USD)
                        .add(sum(refunds.stream().filter(p -> p.getCurrency() == Payment.Currency.USD).map(Payment::getAmount).toList())),
                sum(moves, CashMovement.Direction.OUT, Payment.Currency.KHR)
                        .add(sum(refunds.stream().filter(p -> p.getCurrency() == Payment.Currency.KHR).map(Payment::getAmount).toList())));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(List<CashMovement> moves, CashMovement.Direction direction, Payment.Currency currency) {
        return sum(moves.stream().filter(m -> m.getDirection() == direction && m.getCurrency() == currency)
                .map(CashMovement::getAmount).toList());
    }

    private CashShiftResponse toResponse(CashShift s) {
        Totals t = totals(s);
        BigDecimal expectedUsd = s.getStatus() == CashShift.Status.CLOSED ? s.getExpectedUsd() : t.expectedUsd(s);
        BigDecimal expectedKhr = s.getStatus() == CashShift.Status.CLOSED ? s.getExpectedKhr() : t.expectedKhr(s);
        String cashier = users.findById(s.getCashierId()).map(u -> u.getFullName()).orElse(null);
        return new CashShiftResponse(s.getId(), s.getBranchId(), s.getCashierId(), cashier, s.getStatus(),
                s.getOpenedAt(), s.getClosedAt(), s.getOpeningUsd(), s.getOpeningKhr(), t.receivedUsd(),
                t.receivedKhr(), t.changeUsd(), t.changeKhr(), t.addedUsd(), t.addedKhr(), t.takenUsd(), t.takenKhr(),
                expectedUsd, expectedKhr, s.getCountedUsd(), s.getCountedKhr(),
                s.getCountedUsd() == null ? null : s.getCountedUsd().subtract(expectedUsd),
                s.getCountedKhr() == null ? null : s.getCountedKhr().subtract(expectedKhr),
                receipts.countByCashShiftIdAndStatus(s.getId(), SalesReceipt.Status.POSTED),
                receipts.countByCashShiftIdAndStatus(s.getId(), SalesReceipt.Status.VOID));
    }

    private static boolean short_(BigDecimal counted, BigDecimal expected) {
        return counted != null && expected != null && counted.compareTo(expected) < 0;
    }
}
