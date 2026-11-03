package com.modernstock.purchase;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BranchScope;
import com.modernstock.common.PageResponse;
import com.modernstock.money.Allocation;
import com.modernstock.money.AllocationRepository;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentRepository;
import com.modernstock.money.PaymentResponse;
import com.modernstock.purchase.PurchaseService.BillResponse;
import com.modernstock.purchase.PurchaseService.BillUpdateRequest;
import com.modernstock.purchase.PurchaseService.GoodsReceiptResponse;
import com.modernstock.purchase.PurchaseService.PurchaseOrderRequest;
import com.modernstock.purchase.PurchaseService.PurchaseOrderResponse;
import com.modernstock.purchase.PurchaseService.QuickPurchaseRequest;
import com.modernstock.purchase.PurchaseService.QuickPurchaseResponse;
import com.modernstock.purchase.PurchaseService.GoodsReceiptRequest;
import com.modernstock.purchase.PurchaseService.SupplierPaymentRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Purchases")
@RestController
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchases;
    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final BranchScope scope;

    @GetMapping("/purchase-orders")
    @PreAuthorize("hasAuthority('purchase_order:view')")
    public PageResponse<PurchaseOrderResponse> listPurchaseOrders(@RequestParam(required = false) PurchaseOrder.Status status,
                                                    @RequestParam(required = false) Long supplierId,
                                                    @ParameterObject Pageable pageable) {
        return purchases.list(status, supplierId, pageable);
    }

    @GetMapping("/purchase-orders/{id}")
    @PreAuthorize("hasAuthority('purchase_order:view')")
    public PurchaseOrderResponse getPurchaseOrder(@PathVariable Long id) {
        return purchases.get(id);
    }

    @Operation(summary = "Create a draft purchase order", description = "No approval step: only the Owner and Manager can create them.")
    @PostMapping("/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('purchase_order:manage')")
    public PurchaseOrderResponse createPurchaseOrder(@Valid @RequestBody PurchaseOrderRequest req) {
        return purchases.create(req);
    }

    @PutMapping("/purchase-orders/{id}")
    @PreAuthorize("hasAuthority('purchase_order:manage')")
    public PurchaseOrderResponse updatePurchaseOrder(@PathVariable Long id, @Valid @RequestBody PurchaseOrderRequest req) {
        return purchases.update(id, req);
    }

    @DeleteMapping("/purchase-orders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('purchase_order:manage')")
    public void deletePurchaseOrder(@PathVariable Long id) {
        purchases.deleteDraft(id);
    }

    @Operation(summary = "Mark as ordered (sent to the supplier)")
    @PostMapping("/purchase-orders/{id}/order")
    @PreAuthorize("hasAuthority('purchase_order:manage')")
    public PurchaseOrderResponse markOrdered(@PathVariable Long id) {
        return purchases.order(id);
    }

    @Operation(summary = "Cancel a draft or ordered purchase order")
    @PostMapping("/purchase-orders/{id}/cancel")
    @PreAuthorize("hasAuthority('purchase_order:manage')")
    public PurchaseOrderResponse cancelPurchaseOrder(@PathVariable Long id) {
        return purchases.cancel(id);
    }

    @Operation(summary = "Close a partly received order", description = "Stop waiting for the rest.")
    @PostMapping("/purchase-orders/{id}/close")
    @PreAuthorize("hasAuthority('purchase_order:manage')")
    public PurchaseOrderResponse closePurchaseOrder(@PathVariable Long id) {
        return purchases.close(id);
    }

    @Operation(summary = "Receive goods",
            description = "Creates a posted goods receipt (stock up, average cost updated) and its bill in one transaction. "
                    + "Warnings list quantity or price differences from the order (three-way match).")
    @PostMapping("/purchase-orders/{id}/receive")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('goods_receipt:create')")
    public GoodsReceiptResponse receiveGoods(@PathVariable Long id, @Valid @RequestBody GoodsReceiptRequest req) {
        return purchases.receive(id, req);
    }

    @Operation(summary = "Save, receive and pay now",
            description = "For market buying: purchase order, goods receipt, bill and (optionally) payment from one screen.")
    @PostMapping("/purchase-orders/quick")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('purchase_order:manage') and hasAuthority('goods_receipt:create')")
    public QuickPurchaseResponse quickPurchase(@Valid @RequestBody QuickPurchaseRequest req) {
        return purchases.quick(req);
    }

    @GetMapping("/goods-receipts")
    @PreAuthorize("hasAnyAuthority('purchase_order:view', 'goods_receipt:create')")
    public PageResponse<GoodsReceiptResponse> listGoodsReceipts(@RequestParam(required = false) Long purchaseOrderId,
                                                       @ParameterObject Pageable pageable) {
        return purchases.receipts(purchaseOrderId, pageable);
    }

    @GetMapping("/goods-receipts/{id}")
    @PreAuthorize("hasAnyAuthority('purchase_order:view', 'goods_receipt:create')")
    public GoodsReceiptResponse getGoodsReceipt(@PathVariable Long id) {
        return purchases.receipt(id);
    }

    @Operation(summary = "Bills", description = "Sorted by due date. overdue is calculated from the due date.")
    @GetMapping("/bills")
    @PreAuthorize("hasAuthority('bill:manage')")
    public PageResponse<BillResponse> listBills(@RequestParam(required = false) Long supplierId,
                                            @RequestParam(required = false) Bill.Status status,
                                            @ParameterObject Pageable pageable) {
        return purchases.bills(supplierId, status, pageable);
    }

    @GetMapping("/bills/{id}")
    @PreAuthorize("hasAuthority('bill:manage')")
    public BillResponse getBill(@PathVariable Long id) {
        return purchases.bill(id);
    }

    @Operation(summary = "Add the supplier's invoice number, change the due date or note")
    @PatchMapping("/bills/{id}")
    @PreAuthorize("hasAuthority('bill:manage')")
    public BillResponse updateBill(@PathVariable Long id, @Valid @RequestBody BillUpdateRequest req) {
        return purchases.updateBill(id, req);
    }

    @Operation(summary = "Pay a supplier", description = "Pays the listed bills, or the oldest due bills first. Money left over is an advance.")
    @PostMapping("/supplier-payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('bill:manage')")
    public PaymentResponse paySupplier(@Valid @RequestBody SupplierPaymentRequest req) {
        return purchases.paySupplier(req);
    }

    @GetMapping("/supplier-payments")
    @PreAuthorize("hasAuthority('bill:manage')")
    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listSupplierPayments(@RequestParam(required = false) Long supplierId,
                                                          @ParameterObject Pageable pageable) {
        return PageResponse.of(payments.search(Payment.Direction.OUT, scope.branchIds(), supplierId, pageable),
                p -> PaymentResponse.from(p, allocations.findByFromTypeAndFromId(Allocation.FromType.PAYMENT, p.getId())));
    }
}
