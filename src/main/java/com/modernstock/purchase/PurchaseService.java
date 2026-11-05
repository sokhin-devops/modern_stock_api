package com.modernstock.purchase;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.modernstock.activity.ActivityService;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BranchService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.Warehouse;
import com.modernstock.business.WarehouseRepository;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.FileStorage;
import com.modernstock.common.PageResponse;
import com.modernstock.currency.CurrencyService;
import com.modernstock.inventory.StockMovement;
import com.modernstock.inventory.StockService;
import com.modernstock.money.Allocation;
import com.modernstock.money.AllocationRepository;
import com.modernstock.money.AllocationRequest;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentRepository;
import com.modernstock.money.PaymentResponse;
import com.modernstock.money.PaymentService.PaymentLine;
import com.modernstock.money.PaymentService;
import com.modernstock.partner.Supplier;
import com.modernstock.partner.SupplierRepository;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Purchase order → goods receipt (stock up) → bill (debt up) → supplier payment (debt down), PLAIN.md 11.7.
 * Bills are created from goods receipts with the confirmed prices, so a bill always matches the stock cost.
 */
@Service
@RequiredArgsConstructor
public class PurchaseService {

    public static final String GRN_REF = "GOODS_RECEIPT";

    public record PoLineRequest(@NotNull Long productId,
                                @Schema(description = "Empty = base unit") Long unitId,
                                @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
                                @Schema(description = "Cost of one unitId, USD") @NotNull @DecimalMin("0") BigDecimal unitCost) {
    }

    public record PurchaseOrderRequest(@NotNull Long supplierId,
                                       @Schema(description = "Defaults to the selected branch") Long branchId,
                                       @Schema(description = "Defaults to the branch's receiving warehouse") Long receiveWarehouseId,
                                       LocalDate orderDate,
                                       LocalDate expectedDate,
                                       @DecimalMin("0") BigDecimal discount,
                                       @Size(max = 500) String note,
                                       @NotEmpty List<@Valid PoLineRequest> lines) {
    }

    public record PoLineResponse(Long id, Long productId, String productName, Long unitId, String unitSymbol,
                                 BigDecimal qtyOrdered, BigDecimal qtyReceived, BigDecimal unitCost,
                                 BigDecimal lineTotal) {
    }

    public record PurchaseOrderResponse(Long id, String number, Long supplierId, String supplierName, Long branchId,
                                        Long receiveWarehouseId, String receiveWarehouseName, LocalDate orderDate,
                                        LocalDate expectedDate, BigDecimal exchangeRate, BigDecimal subtotal,
                                        BigDecimal discount, BigDecimal vat, BigDecimal total,
                                        PurchaseOrder.Status status, String note, Instant createdAt,
                                        List<PoLineResponse> lines) {
    }

    public record GoodsReceiptLineRequest(@NotNull Long purchaseOrderItemId,
                                     @Schema(description = "In the order line's unit") @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
                                     @Schema(description = "Confirmed cost; empty = the ordered cost") @DecimalMin("0") BigDecimal unitCost,
                                     @Size(max = 40) String batchNo,
                                     @Schema(description = "Required for products that track expiry") LocalDate expiryDate) {
    }

    public record GoodsReceiptRequest(@Schema(description = "Defaults to the order's receiving warehouse") Long warehouseId,
                                 @Size(max = 40) String supplierInvoiceNo,
                                 @Size(max = 500) String note,
                                 @NotEmpty List<@Valid GoodsReceiptLineRequest> lines) {
    }

    public record GrnLineResponse(Long id, Long purchaseOrderItemId, Long productId, String productName, Long unitId,
                                  String unitSymbol, BigDecimal qty, BigDecimal baseQty, BigDecimal unitCost, String batchNo,
                                  LocalDate expiryDate) {
    }

    public record GoodsReceiptResponse(Long id, String number, Long purchaseOrderId, Long supplierId, String supplierName,
                                       Long branchId, Long warehouseId,
                                       String warehouseName, Instant receivedAt, GoodsReceipt.Status status,
                                       Long billId, String billNumber, BigDecimal total,
                                       @Schema(description = "Three-way match: quantity or price differs from the order")
                                       List<String> warnings, List<GrnLineResponse> lines) {
    }

    public record BillResponse(Long id, String number, Long supplierId, String supplierName, Long branchId,
                               Long goodsReceiptId, String supplierInvoiceNo, LocalDate billDate, LocalDate dueDate,
                               BigDecimal total, BigDecimal paidAmount, BigDecimal balance, Bill.Status status,
                               boolean overdue, String note,
                               @Schema(description = "A photo of the supplier's invoice is attached (GET /bills/{id}/photo)") boolean hasPhoto) {
    }

    public record BillUpdateRequest(@Size(max = 40) String supplierInvoiceNo, LocalDate dueDate,
                                    @Size(max = 500) String note) {
    }

    public record QuickLineRequest(@NotNull Long productId, Long unitId,
                                   @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
                                   @NotNull @DecimalMin("0") BigDecimal unitCost,
                                   @Size(max = 40) String batchNo, LocalDate expiryDate) {
    }

    public record QuickPurchaseRequest(@Schema(description = "Can be the \"Market\" supplier") @NotNull Long supplierId,
                                       Long branchId, Long warehouseId,
                                       @Size(max = 40) String supplierInvoiceNo,
                                       @Size(max = 500) String note,
                                       @NotEmpty List<@Valid QuickLineRequest> lines,
                                       @Schema(description = "Pay now; empty = the bill stays unpaid") @Valid PaymentLine payment) {
    }

    public record QuickPurchaseResponse(PurchaseOrderResponse purchaseOrder, GoodsReceiptResponse goodsReceipt,
                                        BillResponse bill, PaymentResponse payment) {
    }

    public record SupplierPaymentRequest(@NotNull Long supplierId,
                                         Long branchId,
                                         @NotNull @Valid PaymentLine payment,
                                         @Schema(description = "Bills to pay (USD). Empty = oldest due bills first")
                                         List<@Valid AllocationRequest> allocations,
                                         @Size(max = 500) String note) {
    }

    private final PurchaseOrderRepository orders;
    private final ActivityService activity;
    private final GoodsReceiptRepository receipts;
    private final BillRepository bills;
    private final SupplierRepository suppliers;
    private final WarehouseRepository warehouses;
    private final AllocationRepository allocations;
    private final ProductService products;
    private final StockService stock;
    private final PaymentService payments;
    private final PaymentRepository paymentRecords;
    private final CurrencyService currency;
    private final DocumentNumberService numbers;
    private final BranchService branches;
    private final FileStorage files;
    private final BranchScope scope;

    // ------------------------------------------------------------------ purchase orders

    @Transactional
    public PurchaseOrderResponse create(PurchaseOrderRequest req) {
        PurchaseOrder po = new PurchaseOrder();
        po.setNumber(numbers.next(DocType.PO));
        po.setStatus(PurchaseOrder.Status.DRAFT);
        apply(po, req.supplierId(), req.branchId(), req.receiveWarehouseId(), req.orderDate(), req.expectedDate(),
                req.discount(), req.note(), req.lines());
        orders.save(po);
        return toResponse(po);
    }

    @Transactional
    public PurchaseOrderResponse update(Long id, PurchaseOrderRequest req) {
        PurchaseOrder po = owned(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT) {
            throw ApiException.rule("DOCUMENT_NOT_EDITABLE");
        }
        apply(po, req.supplierId(), req.branchId(), req.receiveWarehouseId(), req.orderDate(), req.expectedDate(),
                req.discount(), req.note(), req.lines());
        return toResponse(po);
    }

    @Transactional
    public void deleteDraft(Long id) {
        PurchaseOrder po = owned(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT) {
            throw ApiException.rule("DOCUMENT_NOT_EDITABLE");
        }
        orders.delete(po);
    }

    @Transactional
    public PurchaseOrderResponse order(Long id) {
        return changeStatus(id, EnumSet.of(PurchaseOrder.Status.DRAFT), PurchaseOrder.Status.ORDERED);
    }

    @Transactional
    public PurchaseOrderResponse cancel(Long id) {
        return changeStatus(id, EnumSet.of(PurchaseOrder.Status.DRAFT, PurchaseOrder.Status.ORDERED),
                PurchaseOrder.Status.CANCELLED);
    }

    /** Stop waiting for the rest of a partly received order. */
    @Transactional
    public PurchaseOrderResponse close(Long id) {
        return changeStatus(id, EnumSet.of(PurchaseOrder.Status.PARTIALLY_RECEIVED), PurchaseOrder.Status.CLOSED);
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderResponse> list(PurchaseOrder.Status status, Long supplierId, Pageable pageable) {
        return PageResponse.of(orders.search(scope.branchIds(), status, supplierId, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(Long id) {
        return toResponse(owned(id));
    }

    // ------------------------------------------------------------------ receiving

    /** Goods receipt (stock up, average cost updated) and its bill, in one transaction. */
    @Transactional
    public GoodsReceiptResponse receive(Long purchaseOrderId, GoodsReceiptRequest req) {
        PurchaseOrder po = owned(purchaseOrderId);
        if (!EnumSet.of(PurchaseOrder.Status.DRAFT, PurchaseOrder.Status.ORDERED,
                PurchaseOrder.Status.PARTIALLY_RECEIVED).contains(po.getStatus())) {
            throw ApiException.rule("WRONG_STATUS", po.getStatus());
        }
        Warehouse w = warehouses.get(req.warehouseId() != null ? req.warehouseId() : po.getReceiveWarehouseId());
        if (!w.getBranchId().equals(po.getBranchId()) || !w.isActive()) {
            throw ApiException.rule("WAREHOUSE_NOT_IN_BRANCH");
        }

        GoodsReceipt grn = new GoodsReceipt();
        grn.setNumber(numbers.next(DocType.GRN));
        grn.setPurchaseOrderId(po.getId());
        grn.setBranchId(po.getBranchId());
        grn.setWarehouseId(w.getId());
        grn.setReceivedAt(Instant.now());
        grn.setPostedAt(grn.getReceivedAt());
        grn.setStatus(GoodsReceipt.Status.POSTED);
        grn.setNote(req.note());
        receipts.save(grn);

        List<String> warnings = new ArrayList<>();
        StockService.Ref ref = new StockService.Ref(GRN_REF, grn.getId());
        List<GoodsReceiptLineRequest> sorted = req.lines().stream()
                .sorted(Comparator.comparing(l -> itemOf(po, l.purchaseOrderItemId()).getProductId())).toList();
        for (GoodsReceiptLineRequest line : sorted) {
            PurchaseOrderItem poItem = itemOf(po, line.purchaseOrderItemId());
            Product p = products.active(poItem.getProductId());
            if (p.isTrackExpiry() && line.expiryDate() == null) {
                throw ApiException.rule("EXPIRY_REQUIRED", p.getNameEn());
            }
            BigDecimal unitCost = line.unitCost() != null ? line.unitCost() : poItem.getUnitCost();
            BigDecimal factor = products.factor(p, poItem.getUnitId());
            BigDecimal baseQty = line.qty().multiply(factor);
            stock.receive(p, w.getId(), baseQty, unitCost.divide(factor, 4, RoundingMode.HALF_UP), line.batchNo(),
                    line.expiryDate(), StockMovement.Type.GOODS_RECEIPT, ref);

            poItem.setQtyReceived(poItem.getQtyReceived().add(line.qty()));
            if (poItem.getQtyReceived().compareTo(poItem.getQtyOrdered()) > 0) {
                warnings.add(p.getNameEn() + ": received " + poItem.getQtyReceived().stripTrailingZeros().toPlainString()
                        + ", ordered " + poItem.getQtyOrdered().stripTrailingZeros().toPlainString());
            }
            if (unitCost.compareTo(poItem.getUnitCost()) != 0) {
                warnings.add(p.getNameEn() + ": price " + unitCost.stripTrailingZeros().toPlainString()
                        + ", ordered at " + poItem.getUnitCost().stripTrailingZeros().toPlainString());
            }
            GoodsReceiptItem item = new GoodsReceiptItem();
            item.setPurchaseOrderItemId(poItem.getId());
            item.setProductId(p.getId());
            item.setUnitId(poItem.getUnitId());
            item.setQty(line.qty());
            item.setBaseQty(baseQty);
            item.setUnitCost(unitCost);
            item.setBatchNo(line.batchNo());
            item.setExpiryDate(line.expiryDate());
            grn.getItems().add(item);
        }

        boolean complete = po.getItems().stream().allMatch(i -> i.getQtyReceived().compareTo(i.getQtyOrdered()) >= 0);
        po.setStatus(complete ? PurchaseOrder.Status.RECEIVED : PurchaseOrder.Status.PARTIALLY_RECEIVED);

        Supplier supplier = suppliers.get(po.getSupplierId());
        BigDecimal subtotal = grn.getItems().stream().map(i -> i.getQty().multiply(i.getUnitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        Bill bill = new Bill();
        bill.setNumber(numbers.next(DocType.BILL));
        bill.setSupplierId(supplier.getId());
        bill.setBranchId(po.getBranchId());
        bill.setGoodsReceiptId(grn.getId());
        bill.setSupplierInvoiceNo(req.supplierInvoiceNo());
        bill.setBillDate(Dates.today());
        bill.setDueDate(Dates.today().plusDays(supplier.getPaymentTermsDays()));
        bill.setExchangeRate(po.getExchangeRate());
        bill.setSubtotal(subtotal);
        bill.setTotal(subtotal);
        bill.setStatus(subtotal.signum() == 0 ? Bill.Status.PAID : Bill.Status.UNPAID);
        bills.save(bill);
        useAdvances(bill);
        return toResponse(grn, bill, warnings);
    }

    /** "Save, receive and pay now": purchase order, goods receipt, bill and payment from one screen. */
    @Transactional
    public QuickPurchaseResponse quick(QuickPurchaseRequest req) {
        if (req.payment() != null && !BusinessContext.has("bill:manage")) {
            throw ApiException.forbidden("PERMISSION_DENIED");
        }
        PurchaseOrder po = new PurchaseOrder();
        po.setNumber(numbers.next(DocType.PO));
        po.setStatus(PurchaseOrder.Status.ORDERED);
        List<PoLineRequest> poLines = req.lines().stream()
                .map(l -> new PoLineRequest(l.productId(), l.unitId(), l.qty(), l.unitCost())).toList();
        apply(po, req.supplierId(), req.branchId(), req.warehouseId(), null, null, null, req.note(), poLines);
        orders.save(po);

        List<GoodsReceiptLineRequest> receiveLines = new ArrayList<>();
        for (int i = 0; i < req.lines().size(); i++) {
            QuickLineRequest l = req.lines().get(i);
            receiveLines.add(new GoodsReceiptLineRequest(po.getItems().get(i).getId(), l.qty(), l.unitCost(), l.batchNo(),
                    l.expiryDate()));
        }
        GoodsReceiptResponse grn = receive(po.getId(), new GoodsReceiptRequest(req.warehouseId(), req.supplierInvoiceNo(),
                req.note(), receiveLines));
        Bill bill = bills.get(grn.billId());
        PaymentResponse payment = null;
        if (req.payment() != null) {
            payment = paySupplier(new SupplierPaymentRequest(po.getSupplierId(), po.getBranchId(), req.payment(),
                    List.of(new AllocationRequest(bill.getId(),
                            bill.open().min(PaymentService.usd(req.payment(), po.getExchangeRate())))), req.note()));
        }
        return new QuickPurchaseResponse(toResponse(po), grn, toResponse(bill), payment);
    }

    @Transactional(readOnly = true)
    public PageResponse<GoodsReceiptResponse> receipts(Long purchaseOrderId, Pageable pageable) {
        return PageResponse.of(receipts.search(scope.branchIds(), purchaseOrderId, pageable),
                g -> toResponse(g, bills.findByGoodsReceiptId(g.getId()).orElse(null), List.of()));
    }

    @Transactional(readOnly = true)
    public GoodsReceiptResponse receipt(Long id) {
        GoodsReceipt g = receipts.get(id);
        BusinessContext.requireBranch(g.getBranchId());
        return toResponse(g, bills.findByGoodsReceiptId(id).orElse(null), List.of());
    }

    // ------------------------------------------------------------------ bills and supplier payments

    @Transactional(readOnly = true)
    public PageResponse<BillResponse> bills(Long supplierId, Bill.Status status, Pageable pageable) {
        return PageResponse.of(bills.search(scope.branchIds(), supplierId, status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public BillResponse bill(Long id) {
        Bill b = bills.get(id);
        BusinessContext.requireBranch(b.getBranchId());
        return toResponse(b);
    }

    /** The stored photo of a bill's paper invoice, for someone who may see the bill. */
    @Transactional(readOnly = true)
    public String billPhoto(Long id) {
        Bill b = bills.get(id);
        BusinessContext.requireBranch(b.getBranchId());
        return b.getPhotoPath();
    }

    /** Attaches (or replaces) the photo of the supplier's paper invoice; null removes it. */
    @Transactional
    public BillResponse setBillPhoto(Long id, MultipartFile file) {
        Bill b = bills.get(id);
        BusinessContext.requireBranch(b.getBranchId());
        String old = b.getPhotoPath();
        b.setPhotoPath(file == null ? null : files.saveImage(file, FileStorage.PRIVATE_DOCUMENTS));
        files.delete(old);
        return toResponse(b);
    }

    @Transactional
    public BillResponse updateBill(Long id, BillUpdateRequest req) {
        Bill b = bills.get(id);
        BusinessContext.requireBranch(b.getBranchId());
        if (req.supplierInvoiceNo() != null) {
            b.setSupplierInvoiceNo(req.supplierInvoiceNo());
        }
        if (req.dueDate() != null) {
            b.setDueDate(req.dueDate());
        }
        if (req.note() != null) {
            b.setNote(req.note());
        }
        return toResponse(b);
    }

    /** Pays bills; money left over is an advance used on the next bill. */
    @Transactional
    public PaymentResponse paySupplier(SupplierPaymentRequest req) {
        Supplier supplier = suppliers.get(req.supplierId());
        Long branchId = BusinessContext.branchForWrite(req.branchId());
        BigDecimal rate = currency.current().usdToKhr();
        Payment p = payments.record(Payment.Direction.OUT, Payment.PartyType.SUPPLIER, supplier.getId(), branchId,
                req.payment(), rate, null, req.note());
        BigDecimal left = p.getAmountUsd();
        if (req.allocations() != null && !req.allocations().isEmpty()) {
            for (AllocationRequest a : req.allocations()) {
                Bill b = bills.get(a.documentId());
                if (!b.getSupplierId().equals(supplier.getId()) || b.getStatus() == Bill.Status.VOID) {
                    throw ApiException.notFound("Bill");
                }
                if (a.amount().compareTo(b.open()) > 0 || a.amount().compareTo(left) > 0) {
                    throw ApiException.rule("ALLOCATION_TOO_BIG");
                }
                b.pay(a.amount());
                payments.allocate(p, Allocation.ToType.BILL, b.getId(), a.amount());
                left = left.subtract(a.amount());
            }
        } else {
            for (Bill b : bills.findBySupplierIdAndStatusInOrderByDueDateAscIdAsc(supplier.getId(),
                    EnumSet.of(Bill.Status.UNPAID, Bill.Status.PARTLY_PAID))) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal amount = left.min(b.open());
                b.pay(amount);
                payments.allocate(p, Allocation.ToType.BILL, b.getId(), amount);
                left = left.subtract(amount);
            }
        }
        return PaymentResponse.from(p, allocations.findByFromTypeAndFromId(Allocation.FromType.PAYMENT, p.getId()));
    }

    // ------------------------------------------------------------------ helpers

    /** Money paid to the supplier before a bill existed (an advance) pays the next bill, oldest first (PLAIN.md 11.7). */
    private void useAdvances(Bill bill) {
        for (Payment p : paymentRecords.unallocated(Payment.PartyType.SUPPLIER, bill.getSupplierId(), Payment.Direction.OUT)) {
            if (bill.open().signum() <= 0) {
                break;
            }
            BigDecimal amount = p.getAmountUsd().subtract(allocations.allocated(Allocation.FromType.PAYMENT, p.getId()))
                    .min(bill.open());
            if (amount.signum() > 0) {
                bill.pay(amount);
                payments.allocate(p, Allocation.ToType.BILL, bill.getId(), amount);
            }
        }
    }

    private void apply(PurchaseOrder po, Long supplierId, Long branchId, Long warehouseId, LocalDate orderDate,
                       LocalDate expectedDate, BigDecimal discount, String note, List<PoLineRequest> lines) {
        Supplier supplier = suppliers.get(supplierId);
        Long branch = BusinessContext.branchForWrite(branchId);
        Warehouse w = warehouseId != null ? warehouses.get(warehouseId) : branches.receivingWarehouse(branch);
        if (!w.getBranchId().equals(branch)) {
            throw ApiException.rule("WAREHOUSE_NOT_IN_BRANCH");
        }
        po.setSupplierId(supplier.getId());
        po.setBranchId(branch);
        po.setReceiveWarehouseId(w.getId());
        po.setOrderDate(orderDate != null ? orderDate : Dates.today());
        po.setExpectedDate(expectedDate);
        po.setExchangeRate(currency.current().usdToKhr());
        po.setNote(note);
        po.getItems().clear();
        for (PoLineRequest line : lines) {
            Product p = products.active(line.productId());
            Long unitId = line.unitId() == null ? p.getBaseUnitId() : line.unitId();
            products.factor(p, unitId);
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setProductId(p.getId());
            item.setUnitId(unitId);
            item.setQtyOrdered(line.qty());
            item.setUnitCost(line.unitCost());
            po.getItems().add(item);
        }
        BigDecimal subtotal = po.getItems().stream().map(i -> i.getQtyOrdered().multiply(i.getUnitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal disc = discount == null ? BigDecimal.ZERO : discount.setScale(2, RoundingMode.HALF_UP);
        if (disc.compareTo(subtotal) > 0) {
            throw ApiException.badRequest("DISCOUNT_TOO_BIG");
        }
        po.setSubtotal(subtotal);
        po.setDiscount(disc);
        po.setTotal(subtotal.subtract(disc));
    }

    private PurchaseOrderResponse changeStatus(Long id, EnumSet<PurchaseOrder.Status> from, PurchaseOrder.Status to) {
        PurchaseOrder po = owned(id);
        if (!from.contains(po.getStatus())) {
            throw ApiException.rule("WRONG_STATUS", po.getStatus());
        }
        po.setStatus(to);
        if (to == PurchaseOrder.Status.CANCELLED) {
            activity.record("PURCHASE_ORDER_CANCELLED", "PURCHASE_ORDER", po.getId(), po.getNumber(), po.getBranchId(), null, null);
        }
        return toResponse(po);
    }

    private PurchaseOrder owned(Long id) {
        PurchaseOrder po = orders.get(id);
        BusinessContext.requireBranch(po.getBranchId());
        return po;
    }

    private static PurchaseOrderItem itemOf(PurchaseOrder po, Long itemId) {
        return po.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Order line " + itemId));
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder po) {
        Map<Long, Product> byId = products.byIds(po.getItems().stream().map(PurchaseOrderItem::getProductId).toList());
        Map<Long, String> symbols = products.unitSymbols();
        List<PoLineResponse> lines = po.getItems().stream().map(i -> new PoLineResponse(i.getId(), i.getProductId(),
                byId.get(i.getProductId()).getNameEn(), i.getUnitId(), symbols.get(i.getUnitId()), i.getQtyOrdered(),
                i.getQtyReceived(), i.getUnitCost(),
                i.getQtyOrdered().multiply(i.getUnitCost()).setScale(2, RoundingMode.HALF_UP))).toList();
        return new PurchaseOrderResponse(po.getId(), po.getNumber(), po.getSupplierId(),
                suppliers.get(po.getSupplierId()).getName(), po.getBranchId(), po.getReceiveWarehouseId(),
                branches.warehouseLabels().get(po.getReceiveWarehouseId()), po.getOrderDate(), po.getExpectedDate(),
                po.getExchangeRate(), po.getSubtotal(), po.getDiscount(), po.getVat(), po.getTotal(), po.getStatus(),
                po.getNote(), po.getCreatedAt(), lines);
    }

    private GoodsReceiptResponse toResponse(GoodsReceipt g, Bill bill, List<String> warnings) {
        Map<Long, Product> byId = products.byIds(g.getItems().stream().map(GoodsReceiptItem::getProductId).toList());
        Map<Long, String> symbols = products.unitSymbols();
        Long supplierId = orders.get(g.getPurchaseOrderId()).getSupplierId();
        List<GrnLineResponse> lines = g.getItems().stream().map(i -> new GrnLineResponse(i.getId(),
                i.getPurchaseOrderItemId(), i.getProductId(), byId.get(i.getProductId()).getNameEn(), i.getUnitId(),
                symbols.get(i.getUnitId()), i.getQty(), i.getBaseQty(), i.getUnitCost(), i.getBatchNo(), i.getExpiryDate())).toList();
        return new GoodsReceiptResponse(g.getId(), g.getNumber(), g.getPurchaseOrderId(), supplierId,
                suppliers.get(supplierId).getName(), g.getBranchId(),
                g.getWarehouseId(), branches.warehouseLabels().get(g.getWarehouseId()), g.getReceivedAt(),
                g.getStatus(), bill == null ? null : bill.getId(), bill == null ? null : bill.getNumber(),
                bill == null ? null : bill.getTotal(), warnings, lines);
    }

    private BillResponse toResponse(Bill b) {
        boolean overdue = b.getStatus() != Bill.Status.PAID && b.getStatus() != Bill.Status.VOID
                && b.getDueDate().isBefore(Dates.today());
        return new BillResponse(b.getId(), b.getNumber(), b.getSupplierId(), suppliers.get(b.getSupplierId()).getName(),
                b.getBranchId(), b.getGoodsReceiptId(), b.getSupplierInvoiceNo(), b.getBillDate(), b.getDueDate(),
                b.getTotal(), b.getPaidAmount(), b.open(), b.getStatus(), overdue, b.getNote(), b.getPhotoPath() != null);
    }
}
