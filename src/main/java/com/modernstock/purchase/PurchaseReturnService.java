package com.modernstock.purchase;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
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
import com.modernstock.partner.SupplierRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Purchase returns and supplier credits (FR-2.7, FR-2.8, PLAIN.md 11.7). A return comes from a goods receipt, at most
 * what was received minus what went back before. The goods leave stock from the batch they came in with (expired ones
 * too), and a supplier credit is made for them at the price they were received at: first used on the receipt's bill,
 * then refunded by the supplier or kept to pay later bills.
 */
@Service
@RequiredArgsConstructor
public class PurchaseReturnService {

    public static final String REF = "PURCHASE_RETURN";

    public record PurchaseReturnableLine(Long goodsReceiptItemId, Long productId, String productName, Long unitId, String unitSymbol,
                                         BigDecimal received, BigDecimal returned, BigDecimal returnable,
                                         @Schema(description = "Cost of one unit as received") BigDecimal unitCost,
                                         String batchNo, LocalDate expiryDate) {
    }

    public record PurchaseReturnable(Long goodsReceiptId, String number, Long supplierId, String supplierName, Long billId,
                                     String billNumber,
                                     @Schema(description = "Still owed on the bill; the credit goes there first") BigDecimal billOpen,
                                     List<PurchaseReturnableLine> lines) {
    }

    public record PurchaseReturnLineRequest(@NotNull Long goodsReceiptItemId,
                                            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty) {
    }

    public record SupplierRefundRequest(@NotNull Payment.Method method, @NotNull Payment.Currency currency) {
    }

    public record PurchaseReturnRequest(@NotNull Long goodsReceiptId, @NotEmpty List<@Valid PurchaseReturnLineRequest> lines,
                                        @Size(max = 255) String reason,
                                        @Schema(description = "The supplier pays back what is left of the credit now")
                                        @Valid SupplierRefundRequest refund) {
    }

    public record PurchaseReturnLineResponse(Long productId, String productName, String unitSymbol, BigDecimal qty,
                                             BigDecimal unitCost, BigDecimal amount, String batchNo, LocalDate expiryDate) {
    }

    public record SupplierCreditResponse(Long id, String number, Long supplierId, String supplierName, Long purchaseReturnId,
                                         Instant issuedAt, BigDecimal amount, BigDecimal usedAmount, BigDecimal refundedAmount,
                                         BigDecimal left, SupplierCredit.Status status, String reason) {
    }

    public record PurchaseReturnResponse(Long id, String number, Long goodsReceiptId, Long supplierId, String supplierName,
                                         Instant returnedAt, BigDecimal total, String reason,
                                         List<PurchaseReturnLineResponse> lines, SupplierCreditResponse credit) {
    }

    public record SupplierCreditRequest(@NotNull Long supplierId, Long branchId,
                                        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                                        @Size(max = 255) String reason) {
    }

    public record SupplierCreditApplyRequest(@NotNull Long billId,
                                             @Schema(description = "Empty = as much as fits") BigDecimal amount) {
    }

    public record SupplierCreditRefundRequest(@NotNull Payment.Method method, @NotNull Payment.Currency currency,
                                              @Schema(description = "USD; empty = all that is left") BigDecimal amount) {
    }

    private final PurchaseReturnRepository returns;
    private final SupplierCreditRepository credits;
    private final GoodsReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final BillRepository bills;
    private final SupplierRepository suppliers;
    private final ProductService products;
    private final StockService stock;
    private final PaymentService payments;
    private final AllocationRepository allocations;
    private final CurrencyService currency;
    private final DocumentNumberService numbers;
    private final BranchScope scope;

    @Transactional(readOnly = true)
    public PurchaseReturnable returnable(Long goodsReceiptId) {
        GoodsReceipt g = postedReceipt(goodsReceiptId);
        Long supplierId = orders.get(g.getPurchaseOrderId()).getSupplierId();
        Bill bill = bills.findByGoodsReceiptId(g.getId()).orElse(null);
        Map<Long, String> symbols = products.unitSymbols();
        Map<Long, Product> byId = products.byIds(g.getItems().stream().map(GoodsReceiptItem::getProductId).toList());
        List<PurchaseReturnableLine> lines = g.getItems().stream().map(i -> {
            BigDecimal returned = returns.returnedQty(i.getId());
            return new PurchaseReturnableLine(i.getId(), i.getProductId(), byId.get(i.getProductId()).getNameEn(), i.getUnitId(),
                    symbols.get(i.getUnitId()), i.getQty(), returned, i.getQty().subtract(returned).max(BigDecimal.ZERO),
                    i.getUnitCost(), i.getBatchNo(), i.getExpiryDate());
        }).toList();
        return new PurchaseReturnable(g.getId(), g.getNumber(), supplierId, supplierName(supplierId),
                bill == null ? null : bill.getId(), bill == null ? null : bill.getNumber(),
                bill == null || bill.getStatus() == Bill.Status.VOID ? null : bill.open(), lines);
    }

    @Transactional
    public PurchaseReturnResponse create(PurchaseReturnRequest req) {
        GoodsReceipt g = postedReceipt(req.goodsReceiptId());
        BusinessContext.branchForWrite(g.getBranchId()); // a read-only branch takes no new work
        Map<Long, GoodsReceiptItem> byId = new HashMap<>();
        g.getItems().forEach(i -> byId.put(i.getId(), i));
        Map<Long, BigDecimal> asked = new HashMap<>();
        for (PurchaseReturnLineRequest l : req.lines()) {
            GoodsReceiptItem gi = byId.get(l.goodsReceiptItemId());
            if (gi == null) {
                throw ApiException.badRequest("RECEIPT_LINE_UNKNOWN");
            }
            BigDecimal total = asked.merge(gi.getId(), l.qty(), BigDecimal::add);
            BigDecimal left = gi.getQty().subtract(returns.returnedQty(gi.getId()));
            if (total.compareTo(left) > 0) {
                throw ApiException.rule("RETURN_TOO_MANY", products.active(gi.getProductId()).getNameEn(),
                        left.stripTrailingZeros().toPlainString());
            }
        }

        PurchaseReturn r = new PurchaseReturn();
        r.setNumber(numbers.next(DocType.PRT));
        r.setBranchId(g.getBranchId());
        r.setWarehouseId(g.getWarehouseId());
        r.setSupplierId(orders.get(g.getPurchaseOrderId()).getSupplierId());
        r.setGoodsReceiptId(g.getId());
        r.setReturnedAt(Instant.now());
        r.setReason(req.reason());
        for (PurchaseReturnLineRequest l : req.lines()) {
            GoodsReceiptItem gi = byId.get(l.goodsReceiptItemId());
            PurchaseReturnItem item = new PurchaseReturnItem();
            item.setGoodsReceiptItemId(gi.getId());
            item.setProductId(gi.getProductId());
            item.setUnitId(gi.getUnitId());
            item.setQty(l.qty());
            item.setBaseQty(l.qty().multiply(gi.getBaseQty()).divide(gi.getQty(), 3, RoundingMode.HALF_UP));
            item.setUnitCost(gi.getUnitCost());
            item.setAmount(l.qty().multiply(gi.getUnitCost()).setScale(2, RoundingMode.HALF_UP));
            item.setBatchNo(gi.getBatchNo());
            item.setExpiryDate(gi.getExpiryDate());
            r.getItems().add(item);
        }
        r.setTotal(r.getItems().stream().map(PurchaseReturnItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        returns.save(r);

        // Out of the batch each line came in with; in product order, like every stock change, so locks never cross
        StockService.Ref received = new StockService.Ref(PurchaseService.GRN_REF, g.getId());
        StockService.Ref ref = new StockService.Ref(REF, r.getId());
        for (PurchaseReturnItem item : r.getItems().stream().sorted(Comparator.comparing(PurchaseReturnItem::getProductId)).toList()) {
            stock.issueReceived(products.active(item.getProductId()), received, r.getWarehouseId(), item.getBatchNo(),
                    item.getExpiryDate(), item.getBaseQty(), StockMovement.Type.PURCHASE_RETURN, ref);
        }

        SupplierCredit sc = null;
        if (r.getTotal().signum() > 0) {
            sc = newCredit(r.getBranchId(), r.getSupplierId(), r.getTotal(), req.reason());
            sc.setPurchaseReturnId(r.getId());
            credits.save(sc);
            Bill bill = bills.findByGoodsReceiptId(g.getId()).orElse(null);
            if (bill != null && bill.getStatus() != Bill.Status.VOID && bill.open().signum() > 0) {
                apply(sc, bill, sc.left().min(bill.open()));
            }
            if (req.refund() != null && sc.left().signum() > 0) {
                refund(sc, req.refund().method(), req.refund().currency(), sc.left());
            }
        }
        return toResponse(r, sc);
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseReturnResponse> list(Long supplierId, Pageable pageable) {
        return PageResponse.of(returns.search(scope.branchIds(), supplierId, pageable),
                r -> toResponse(r, credits.findByPurchaseReturnId(r.getId()).stream().findFirst().orElse(null)));
    }

    @Transactional(readOnly = true)
    public PurchaseReturnResponse get(Long id) {
        PurchaseReturn r = returns.get(id);
        BusinessContext.requireBranch(r.getBranchId());
        return toResponse(r, credits.findByPurchaseReturnId(id).stream().findFirst().orElse(null));
    }

    // ---------------------------------------------------------------- supplier credits

    /** A supplier credit by hand, for a price correction. */
    @Transactional
    public SupplierCreditResponse createCredit(SupplierCreditRequest req) {
        suppliers.get(req.supplierId());
        Long branchId = BusinessContext.branchForWrite(req.branchId());
        SupplierCredit sc = newCredit(branchId, req.supplierId(), req.amount().setScale(2, RoundingMode.HALF_UP), req.reason());
        return toResponse(credits.save(sc));
    }

    @Transactional
    public SupplierCreditResponse applyCredit(Long id, SupplierCreditApplyRequest req) {
        SupplierCredit sc = openCredit(id);
        Bill bill = bills.get(req.billId());
        if (!sc.getSupplierId().equals(bill.getSupplierId())) {
            throw ApiException.rule("SUPPLIER_CREDIT_OTHER");
        }
        if (bill.getStatus() == Bill.Status.VOID || bill.open().signum() <= 0) {
            throw ApiException.rule("BILL_NOT_OPEN");
        }
        BigDecimal most = sc.left().min(bill.open());
        BigDecimal amount = req.amount() == null ? most : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.compareTo(most) > 0) {
            throw ApiException.badRequest("CREDIT_AMOUNT_INVALID", most);
        }
        apply(sc, bill, amount);
        return toResponse(sc);
    }

    @Transactional
    public SupplierCreditResponse refundCredit(Long id, SupplierCreditRefundRequest req) {
        SupplierCredit sc = openCredit(id);
        BigDecimal amount = req.amount() == null ? sc.left() : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.compareTo(sc.left()) > 0) {
            throw ApiException.badRequest("CREDIT_AMOUNT_INVALID", sc.left());
        }
        refund(sc, req.method(), req.currency(), amount);
        return toResponse(sc);
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierCreditResponse> credits(Long supplierId, SupplierCredit.Status status, Pageable pageable) {
        return PageResponse.of(credits.search(scope.branchIds(), supplierId, status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierCreditResponse credit(Long id) {
        SupplierCredit sc = credits.get(id);
        BusinessContext.requireBranch(sc.getBranchId());
        return toResponse(sc);
    }

    // ---------------------------------------------------------------- helpers

    private GoodsReceipt postedReceipt(Long id) {
        GoodsReceipt g = receipts.get(id);
        BusinessContext.requireBranch(g.getBranchId());
        if (g.getStatus() != GoodsReceipt.Status.POSTED) {
            throw ApiException.rule("RETURN_RECEIPT_VOID");
        }
        return g;
    }

    private SupplierCredit newCredit(Long branchId, Long supplierId, BigDecimal amount, String reason) {
        SupplierCredit sc = new SupplierCredit();
        sc.setNumber(numbers.next(DocType.SC));
        sc.setBranchId(branchId);
        sc.setSupplierId(supplierId);
        sc.setIssuedAt(Instant.now());
        sc.setAmount(amount);
        sc.setReason(reason);
        return sc;
    }

    private void apply(SupplierCredit sc, Bill bill, BigDecimal amount) {
        Allocation a = new Allocation();
        a.setFromType(Allocation.FromType.SUPPLIER_CREDIT);
        a.setFromId(sc.getId());
        a.setToType(Allocation.ToType.BILL);
        a.setToId(bill.getId());
        a.setAmount(amount);
        allocations.save(a);
        bill.pay(amount);
        sc.use(amount);
    }

    /** Money the supplier pays back; it comes in like a payment received, and does not pay any bill. */
    private void refund(SupplierCredit sc, Payment.Method method, Payment.Currency cur, BigDecimal usd) {
        BigDecimal rate = currency.current().usdToKhr();
        BigDecimal amount = cur == Payment.Currency.KHR ? usd.multiply(rate).setScale(0, RoundingMode.HALF_UP) : usd;
        payments.record(Payment.Direction.IN, Payment.PartyType.SUPPLIER, sc.getSupplierId(), sc.getBranchId(),
                new PaymentLine(method, cur, amount, null), rate, null, sc.getNumber());
        sc.refund(usd);
    }

    private SupplierCredit openCredit(Long id) {
        SupplierCredit sc = credits.get(id);
        BusinessContext.requireBranch(sc.getBranchId());
        if (sc.getStatus() != SupplierCredit.Status.OPEN || sc.left().signum() <= 0) {
            throw ApiException.rule("SUPPLIER_CREDIT_NOT_OPEN");
        }
        return sc;
    }

    private String supplierName(Long id) {
        return suppliers.findById(id).map(s -> s.getName()).orElse(null);
    }

    private PurchaseReturnResponse toResponse(PurchaseReturn r, SupplierCredit sc) {
        Map<Long, String> symbols = products.unitSymbols();
        Map<Long, Product> byId = products.byIds(r.getItems().stream().map(PurchaseReturnItem::getProductId).toList());
        return new PurchaseReturnResponse(r.getId(), r.getNumber(), r.getGoodsReceiptId(), r.getSupplierId(),
                supplierName(r.getSupplierId()), r.getReturnedAt(), r.getTotal(), r.getReason(),
                r.getItems().stream().map(i -> new PurchaseReturnLineResponse(i.getProductId(), byId.get(i.getProductId()).getNameEn(),
                        symbols.get(i.getUnitId()), i.getQty(), i.getUnitCost(), i.getAmount(), i.getBatchNo(),
                        i.getExpiryDate())).toList(),
                sc == null ? null : toResponse(sc));
    }

    private SupplierCreditResponse toResponse(SupplierCredit sc) {
        return new SupplierCreditResponse(sc.getId(), sc.getNumber(), sc.getSupplierId(), supplierName(sc.getSupplierId()),
                sc.getPurchaseReturnId(), sc.getIssuedAt(), sc.getAmount(), sc.getUsedAmount(), sc.getRefundedAmount(),
                sc.left(), sc.getStatus(), sc.getReason());
    }
}
