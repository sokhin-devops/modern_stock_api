package com.modernstock.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.auth.User;
import com.modernstock.auth.UserRepository;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.PageResponse;
import com.modernstock.currency.CurrencyService;
import com.modernstock.inventory.StockMovement;
import com.modernstock.inventory.StockService;
import com.modernstock.money.Allocation;
import com.modernstock.money.AllocationRepository;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentService;
import com.modernstock.money.PaymentService.PaymentLine;
import com.modernstock.partner.CustomerRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Sales returns and credit notes (FR-3.12, PLAIN.md 11.8). A return comes from a sales receipt or an invoice, at most
 * what was sold minus what came back before. Good items go back to stock at their cost when sold; damaged ones are a
 * loss. The money given back is the returned lines' share of the sale (discounts and VAT included) and becomes a credit
 * note: first used on the invoice it came from, then refunded or kept as the customer's credit. A walk-in customer is
 * always refunded at once. A Cashier only requests a return (section 5): it waits, moving nothing, until someone who may
 * void sales approves it (then it is posted as if made now, the cash coming out of the Cashier's shift) or rejects it.
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

    public static final String REF = "SALES_RETURN";

    public record ReturnableLine(Long saleItemId, Long productId, String productName, Long unitId, String unitSymbol,
                                 BigDecimal sold, BigDecimal returned, BigDecimal returnable,
                                 @Schema(description = "Money back for one unit (its share of the sale)") BigDecimal unitRefund,
                                 boolean trackExpiry) {
    }

    public record Returnable(Long salesReceiptId, Long invoiceId, String number, Long customerId, String customerName,
                             @Schema(description = "Still owed on the invoice; the credit goes there first") BigDecimal invoiceOpen,
                             List<ReturnableLine> lines) {
    }

    public record ReturnLineRequest(@NotNull Long saleItemId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
                                    @NotNull SalesReturnItem.Condition condition, @Size(max = 40) String batchNo,
                                    @Schema(description = "Needed for good items of a product that tracks expiry") LocalDate expiryDate) {
    }

    public record RefundRequest(@NotNull Payment.Method method, @NotNull Payment.Currency currency) {
    }

    public record ReturnRequest(Long salesReceiptId, Long invoiceId, @NotEmpty List<@Valid ReturnLineRequest> lines,
                                @Size(max = 255) String reason,
                                @Schema(description = "Refund what is left of the credit now; required for a walk-in customer")
                                @Valid RefundRequest refund) {
    }

    public record ReturnLineResponse(Long productId, String productName, String unitSymbol, BigDecimal qty,
                                     SalesReturnItem.Condition condition, BigDecimal refund) {
    }

    public record CreditNoteResponse(Long id, String number, Long customerId, String customerName, Long salesReturnId,
                                     Instant issuedAt, BigDecimal amount, BigDecimal usedAmount, BigDecimal refundedAmount,
                                     BigDecimal left, CreditNote.Status status, String reason) {
    }

    public record ReturnResponse(Long id, String number, Long salesReceiptId, Long invoiceId, String saleNumber, Long customerId,
                                 String customerName, Instant returnedAt, BigDecimal vat, BigDecimal total, BigDecimal lossValue,
                                 String reason, SalesReturn.Status status,
                                 @Schema(description = "Who made it (for a request: the Cashier)") String createdByName,
                                 @Schema(description = "A request: how the customer is paid back once approved") RefundRequest refund,
                                 String reviewedByName, Instant reviewedAt,
                                 List<ReturnLineResponse> lines, CreditNoteResponse creditNote) {
    }

    public record CreditNoteRequest(@NotNull Long customerId, Long branchId,
                                    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                                    @Size(max = 255) String reason) {
    }

    public record ApplyRequest(@NotNull Long invoiceId, @Schema(description = "Empty = as much as fits") BigDecimal amount) {
    }

    public record CreditRefundRequest(@NotNull Payment.Method method, @NotNull Payment.Currency currency,
                                      @Schema(description = "USD; empty = all that is left") BigDecimal amount) {
    }

    private final SalesReturnRepository returns;
    private final CreditNoteRepository creditNotes;
    private final SalesReceiptRepository receipts;
    private final InvoiceRepository invoices;
    private final DeliveryRepository deliveries;
    private final SaleItemRepository saleItems;
    private final CustomerRepository customers;
    private final ProductService products;
    private final StockService stock;
    private final PaymentService payments;
    private final AllocationRepository allocations;
    private final CurrencyService currency;
    private final CashShiftService cashShifts;
    private final DocumentNumberService numbers;
    private final BranchScope scope;
    private final UserRepository users;
    private final ActivityService activity;

    /** The sale behind a return: its lines, total and VAT, and where the goods go back. */
    private record Source(Long receiptId, Long invoiceId, String number, Long branchId, Long warehouseId, Long customerId,
                          BigDecimal total, BigDecimal vat, List<SaleItem> items, Invoice invoice) {
        BigDecimal netLines() {
            return items.stream().map(ReturnService::lineNet).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    @Transactional(readOnly = true)
    public Returnable returnable(Long receiptId, Long invoiceId) {
        Source s = source(receiptId, invoiceId);
        Map<Long, String> symbols = products.unitSymbols();
        Map<Long, Product> byId = products.byIds(s.items().stream().map(SaleItem::getProductId).toList());
        BigDecimal net = s.netLines();
        List<ReturnableLine> lines = s.items().stream().map(si -> {
            BigDecimal returned = returns.returnedQty(si.getId());
            Product p = byId.get(si.getProductId());
            return new ReturnableLine(si.getId(), si.getProductId(), p.getNameEn(), si.getUnitId(), symbols.get(si.getUnitId()),
                    si.getQty(), returned, si.getQty().subtract(returned).max(BigDecimal.ZERO),
                    refund(s, net, unitNet(si)), p.isTrackExpiry());
        }).toList();
        return new Returnable(s.receiptId(), s.invoiceId(), s.number(), s.customerId(), customerName(s.customerId()),
                s.invoice() == null ? null : s.invoice().open(), lines);
    }

    @Transactional
    public ReturnResponse create(ReturnRequest req) {
        boolean canPost = BusinessContext.has("sale:void");
        if (!canPost && !BusinessContext.has("sales_return:request")) {
            throw ApiException.forbidden("PERMISSION_DENIED");
        }
        Source s = source(req.salesReceiptId(), req.invoiceId());
        BusinessContext.branchForWrite(s.branchId()); // a read-only branch takes no new work
        if (s.customerId() == null && req.refund() == null) {
            throw ApiException.badRequest("REFUND_REQUIRED");
        }
        Map<Long, SaleItem> byId = new HashMap<>();
        s.items().forEach(si -> byId.put(si.getId(), si));
        Map<Long, BigDecimal> asked = new HashMap<>();
        for (ReturnLineRequest l : req.lines()) {
            SaleItem si = byId.get(l.saleItemId());
            if (si == null) {
                throw ApiException.badRequest("RETURN_LINE_UNKNOWN");
            }
            BigDecimal total = asked.merge(si.getId(), l.qty(), BigDecimal::add);
            BigDecimal left = si.getQty().subtract(returns.returnedQty(si.getId()));
            if (total.compareTo(left) > 0) {
                throw ApiException.rule("RETURN_TOO_MANY", products.active(si.getProductId()).getNameEn(), left.stripTrailingZeros().toPlainString());
            }
        }

        SalesReturn r = new SalesReturn();
        r.setNumber(numbers.next(DocType.SR));
        r.setBranchId(s.branchId());
        r.setWarehouseId(s.warehouseId());
        r.setCustomerId(s.customerId());
        r.setSalesReceiptId(s.receiptId());
        r.setInvoiceId(s.invoiceId());
        r.setReturnedAt(Instant.now());
        r.setReason(req.reason());
        BigDecimal net = s.netLines();
        BigDecimal returnedNet = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        for (ReturnLineRequest l : req.lines()) {
            SaleItem si = byId.get(l.saleItemId());
            Product p = products.active(si.getProductId());
            if (l.condition() == SalesReturnItem.Condition.GOOD && p.isTrackExpiry() && l.expiryDate() == null) {
                throw ApiException.rule("EXPIRY_REQUIRED", p.getNameEn());
            }
            BigDecimal unitNet = unitNet(si);
            BigDecimal baseQty = l.qty().multiply(si.getBaseQty()).divide(si.getQty(), 3, RoundingMode.HALF_UP);
            SalesReturnItem item = new SalesReturnItem();
            item.setSaleItemId(si.getId());
            item.setProductId(si.getProductId());
            item.setUnitId(si.getUnitId());
            item.setQty(l.qty());
            item.setBaseQty(baseQty);
            item.setUnitNet(unitNet);
            item.setRefund(refund(s, net, unitNet).multiply(l.qty()).setScale(2, RoundingMode.HALF_UP));
            item.setCostAtSale(si.getCostAtSale());
            item.setCondition(l.condition());
            item.setBatchNo(l.batchNo());
            item.setExpiryDate(l.expiryDate());
            r.getItems().add(item);
            returnedNet = returnedNet.add(unitNet.multiply(l.qty()));
            if (l.condition() == SalesReturnItem.Condition.DAMAGED) {
                loss = loss.add(baseQty.multiply(si.getCostAtSale()));
            }
        }
        r.setTotal(r.getItems().stream().map(SalesReturnItem::getRefund).reduce(BigDecimal.ZERO, BigDecimal::add));
        r.setVat(net.signum() == 0 ? BigDecimal.ZERO : s.vat().multiply(returnedNet).divide(net, 2, RoundingMode.HALF_UP));
        r.setLossValue(loss.setScale(2, RoundingMode.HALF_UP));
        if (req.refund() != null) {
            r.setRefundMethod(req.refund().method());
            r.setRefundCurrency(req.refund().currency());
        }
        if (!canPost) {
            r.setStatus(SalesReturn.Status.WAITING_APPROVAL);
            returns.save(r);
            return toResponse(r, null);
        }
        returns.save(r);
        return toResponse(r, post(r, s, CurrentUser.id()));
    }

    /** A Cashier's return, posted now: goods back, credit note, and the refund they asked for from their shift. */
    @Transactional
    public ReturnResponse approve(Long id) {
        SalesReturn r = waiting(id);
        Source s = source(r.getSalesReceiptId(), r.getInvoiceId()); // refused if the sale was voided meanwhile
        BusinessContext.branchForWrite(s.branchId());
        r.setStatus(SalesReturn.Status.POSTED);
        r.setReturnedAt(Instant.now());
        reviewed(r);
        CreditNote cn = post(r, s, r.getCreatedBy());
        activity.record("SALES_RETURN_APPROVED", "SALES_RETURN", r.getId(), r.getNumber(), r.getBranchId(), null,
                ActivityService.values("total", r.getTotal()));
        return toResponse(r, cn);
    }

    @Transactional
    public ReturnResponse reject(Long id) {
        SalesReturn r = waiting(id);
        r.setStatus(SalesReturn.Status.REJECTED);
        reviewed(r);
        activity.record("SALES_RETURN_REJECTED", "SALES_RETURN", r.getId(), r.getNumber(), r.getBranchId(), null,
                ActivityService.values("total", r.getTotal()));
        return toResponse(r, null);
    }

    private SalesReturn waiting(Long id) {
        SalesReturn r = returns.get(id);
        BusinessContext.requireBranch(r.getBranchId());
        if (r.getStatus() != SalesReturn.Status.WAITING_APPROVAL) {
            throw ApiException.rule("WRONG_STATUS", r.getStatus());
        }
        return r;
    }

    private static void reviewed(SalesReturn r) {
        r.setReviewedBy(CurrentUser.idOrNull());
        r.setReviewedAt(Instant.now());
    }

    /**
     * What a posted return does: good items back to stock at their cost when sold, a credit note used on the invoice
     * first, then the refund asked for. Cash comes out of the open shift of whoever served the customer.
     */
    private CreditNote post(SalesReturn r, Source s, Long cashierId) {
        for (SalesReturnItem item : r.getItems()) {
            if (item.getCondition() == SalesReturnItem.Condition.GOOD) {
                stock.receive(products.active(item.getProductId()), r.getWarehouseId(), item.getBaseQty(), item.getCostAtSale(),
                        item.getBatchNo(), item.getExpiryDate(), StockMovement.Type.SALES_RETURN, new StockService.Ref(REF, r.getId()));
            }
        }

        CreditNote cn = null;
        if (r.getTotal().signum() > 0) {
            cn = newCreditNote(r.getBranchId(), r.getCustomerId(), r.getTotal(), r.getReason());
            cn.setSalesReturnId(r.getId());
            creditNotes.save(cn);
            if (s.invoice() != null && s.invoice().open().signum() > 0) {
                apply(cn, s.invoice(), cn.left().min(s.invoice().open()));
            }
            if (r.getRefundMethod() != null && cn.left().signum() > 0) {
                refund(cn, r.getRefundMethod(), r.getRefundCurrency(), cn.left(), cashierId);
            }
        }
        return cn;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReturnResponse> list(SalesReturn.Status status, Pageable pageable) {
        return PageResponse.of(returns.search(scope.branchIds(), status, pageable),
                r -> toResponse(r, creditNotes.findBySalesReturnId(r.getId()).stream().findFirst().orElse(null)));
    }

    @Transactional(readOnly = true)
    public ReturnResponse get(Long id) {
        SalesReturn r = returns.get(id);
        BusinessContext.requireBranch(r.getBranchId());
        return toResponse(r, creditNotes.findBySalesReturnId(id).stream().findFirst().orElse(null));
    }

    // ---------------------------------------------------------------- credit notes

    /** A credit note by hand, for a price correction. */
    @Transactional
    public CreditNoteResponse createCreditNote(CreditNoteRequest req) {
        customers.get(req.customerId());
        Long branchId = BusinessContext.branchForWrite(req.branchId());
        CreditNote cn = newCreditNote(branchId, req.customerId(), req.amount().setScale(2, RoundingMode.HALF_UP), req.reason());
        return toResponse(creditNotes.save(cn));
    }

    @Transactional
    public CreditNoteResponse applyCreditNote(Long id, ApplyRequest req) {
        CreditNote cn = openNote(id);
        Invoice invoice = invoices.get(req.invoiceId());
        if (cn.getCustomerId() == null || !cn.getCustomerId().equals(invoice.getCustomerId())) {
            throw ApiException.rule("CREDIT_OTHER_CUSTOMER");
        }
        if (invoice.getStatus() == Invoice.Status.VOID || invoice.open().signum() <= 0) {
            throw ApiException.rule("INVOICE_NOT_OPEN");
        }
        BigDecimal most = cn.left().min(invoice.open());
        BigDecimal amount = req.amount() == null ? most : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.compareTo(most) > 0) {
            throw ApiException.badRequest("CREDIT_AMOUNT_INVALID", most);
        }
        apply(cn, invoice, amount);
        return toResponse(cn);
    }

    @Transactional
    public CreditNoteResponse refundCreditNote(Long id, CreditRefundRequest req) {
        CreditNote cn = openNote(id);
        BigDecimal amount = req.amount() == null ? cn.left() : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.compareTo(cn.left()) > 0) {
            throw ApiException.badRequest("CREDIT_AMOUNT_INVALID", cn.left());
        }
        refund(cn, req.method(), req.currency(), amount, CurrentUser.id());
        return toResponse(cn);
    }

    @Transactional(readOnly = true)
    public PageResponse<CreditNoteResponse> creditNotes(Long customerId, CreditNote.Status status, Pageable pageable) {
        return PageResponse.of(creditNotes.search(scope.branchIds(), customerId, status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public CreditNoteResponse creditNote(Long id) {
        CreditNote cn = creditNotes.get(id);
        BusinessContext.requireBranch(cn.getBranchId());
        return toResponse(cn);
    }

    // ---------------------------------------------------------------- helpers

    private Source source(Long receiptId, Long invoiceId) {
        if ((receiptId == null) == (invoiceId == null)) {
            throw ApiException.badRequest("RETURN_SOURCE_REQUIRED");
        }
        if (receiptId != null) {
            SalesReceipt rc = receipts.get(receiptId);
            BusinessContext.requireBranch(rc.getBranchId());
            if (rc.getStatus() != SalesReceipt.Status.POSTED) {
                throw ApiException.rule("RETURN_SALE_VOID");
            }
            return new Source(rc.getId(), null, rc.getNumber(), rc.getBranchId(), rc.getWarehouseId(), rc.getCustomerId(),
                    rc.getTotal(), rc.getVat(), saleItems.findBySalesReceiptIdOrderById(rc.getId()), null);
        }
        Invoice inv = invoices.get(invoiceId);
        BusinessContext.requireBranch(inv.getBranchId());
        if (inv.getStatus() == Invoice.Status.VOID || inv.getDeliveryId() == null) {
            throw ApiException.rule("RETURN_SALE_VOID");
        }
        Delivery d = deliveries.get(inv.getDeliveryId());
        return new Source(null, inv.getId(), inv.getNumber(), inv.getBranchId(), d.getWarehouseId(), inv.getCustomerId(),
                inv.getTotal(), inv.getVat(), saleItems.findByDeliveryIdOrderById(d.getId()), inv);
    }

    private static BigDecimal lineNet(SaleItem si) {
        return si.getQty().multiply(si.getUnitPrice()).subtract(si.getDiscount());
    }

    private static BigDecimal unitNet(SaleItem si) {
        return lineNet(si).divide(si.getQty(), 4, RoundingMode.HALF_UP);
    }

    /** One unit's share of the sale's total (so the sale discount and VAT are given back in proportion). */
    private static BigDecimal refund(Source s, BigDecimal netLines, BigDecimal unitNet) {
        return netLines.signum() == 0 ? BigDecimal.ZERO : unitNet.multiply(s.total()).divide(netLines, 4, RoundingMode.HALF_UP);
    }

    private CreditNote newCreditNote(Long branchId, Long customerId, BigDecimal amount, String reason) {
        CreditNote cn = new CreditNote();
        cn.setNumber(numbers.next(DocType.CN));
        cn.setBranchId(branchId);
        cn.setCustomerId(customerId);
        cn.setIssuedAt(Instant.now());
        cn.setAmount(amount);
        cn.setReason(reason);
        return cn;
    }

    private void apply(CreditNote cn, Invoice invoice, BigDecimal amount) {
        Allocation a = new Allocation();
        a.setFromType(Allocation.FromType.CREDIT_NOTE);
        a.setFromId(cn.getId());
        a.setToType(Allocation.ToType.INVOICE);
        a.setToId(invoice.getId());
        a.setAmount(amount);
        allocations.save(a);
        invoice.pay(amount);
        cn.use(amount);
    }

    /** Money out of the till (or bank) to the customer; cash on the cashier's open shift lowers its expected cash. */
    private void refund(CreditNote cn, Payment.Method method, Payment.Currency cur, BigDecimal usd, Long cashierId) {
        BigDecimal rate = currency.current().usdToKhr();
        BigDecimal amount = cur == Payment.Currency.KHR ? usd.multiply(rate).setScale(0, RoundingMode.HALF_UP) : usd;
        Long shiftId = method == Payment.Method.CASH && cashierId != null
                ? cashShifts.openShiftOf(cn.getBranchId(), cashierId).map(CashShift::getId).orElse(null) : null;
        payments.record(Payment.Direction.OUT, cn.getCustomerId() == null ? Payment.PartyType.WALK_IN : Payment.PartyType.CUSTOMER,
                cn.getCustomerId(), cn.getBranchId(), new PaymentLine(method, cur, amount, null), rate, shiftId, cn.getNumber());
        cn.refund(usd);
    }

    private CreditNote openNote(Long id) {
        CreditNote cn = creditNotes.get(id);
        BusinessContext.requireBranch(cn.getBranchId());
        if (cn.getStatus() != CreditNote.Status.OPEN || cn.left().signum() <= 0) {
            throw ApiException.rule("CREDIT_NOT_OPEN");
        }
        return cn;
    }

    private String customerName(Long id) {
        return id == null ? null : customers.findById(id).map(c -> c.getName()).orElse(null);
    }

    private ReturnResponse toResponse(SalesReturn r, CreditNote cn) {
        Map<Long, String> symbols = products.unitSymbols();
        Map<Long, Product> byId = products.byIds(r.getItems().stream().map(SalesReturnItem::getProductId).toList());
        return new ReturnResponse(r.getId(), r.getNumber(), r.getSalesReceiptId(), r.getInvoiceId(), saleNumber(r), r.getCustomerId(),
                customerName(r.getCustomerId()), r.getReturnedAt(), r.getVat(), r.getTotal(), r.getLossValue(), r.getReason(),
                r.getStatus(), userName(r.getCreatedBy()),
                r.getRefundMethod() == null ? null : new RefundRequest(r.getRefundMethod(), r.getRefundCurrency()),
                userName(r.getReviewedBy()), r.getReviewedAt(),
                r.getItems().stream().map(i -> new ReturnLineResponse(i.getProductId(), byId.get(i.getProductId()).getNameEn(),
                        symbols.get(i.getUnitId()), i.getQty(), i.getCondition(), i.getRefund())).toList(),
                cn == null ? null : toResponse(cn));
    }

    private String saleNumber(SalesReturn r) {
        return r.getSalesReceiptId() != null ? receipts.findById(r.getSalesReceiptId()).map(SalesReceipt::getNumber).orElse(null)
                : invoices.findById(r.getInvoiceId()).map(Invoice::getNumber).orElse(null);
    }

    private String userName(Long id) {
        return id == null ? null : users.findById(id).map(User::getFullName).orElse(null);
    }

    private CreditNoteResponse toResponse(CreditNote cn) {
        return new CreditNoteResponse(cn.getId(), cn.getNumber(), cn.getCustomerId(), customerName(cn.getCustomerId()),
                cn.getSalesReturnId(), cn.getIssuedAt(), cn.getAmount(), cn.getUsedAmount(), cn.getRefundedAmount(), cn.left(),
                cn.getStatus(), cn.getReason());
    }
}
