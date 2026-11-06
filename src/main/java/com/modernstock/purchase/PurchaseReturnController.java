package com.modernstock.purchase;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.common.PageResponse;
import com.modernstock.purchase.PurchaseReturnService.PurchaseReturnRequest;
import com.modernstock.purchase.PurchaseReturnService.PurchaseReturnResponse;
import com.modernstock.purchase.PurchaseReturnService.PurchaseReturnable;
import com.modernstock.purchase.PurchaseReturnService.SupplierCreditApplyRequest;
import com.modernstock.purchase.PurchaseReturnService.SupplierCreditRefundRequest;
import com.modernstock.purchase.PurchaseReturnService.SupplierCreditRequest;
import com.modernstock.purchase.PurchaseReturnService.SupplierCreditResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Purchase returns and supplier credits (FR-2.7, FR-2.8). They change what the business owes, so they are for the people
 * who manage bills and supplier payments: Owners and Managers (bill:manage).
 */
@Tag(name = "Purchase returns and supplier credits")
@RestController
@RequiredArgsConstructor
public class PurchaseReturnController {

    private final PurchaseReturnService returns;
    private final PurchaseVoids voids;

    @Operation(summary = "What can still be sent back from a goods receipt")
    @GetMapping("/goods-receipts/{id}/returnable")
    @PreAuthorize("hasAnyAuthority('bill:manage', 'purchase_return:prepare')")
    public PurchaseReturnable returnable(@PathVariable Long id) {
        return returns.returnable(id);
    }

    @Operation(summary = "Send goods back to the supplier",
            description = "From a goods receipt: the goods leave stock from the batch they came in with, expired ones too. A "
                    + "supplier credit is made at the received price: used on the receipt's bill first, then refunded by the "
                    + "supplier (refund) or kept for later bills. 422 RETURN_TOO_MANY when more than was received and not yet "
                    + "returned (or prepared). Without bill:manage (a Stock keeper with purchase_return:prepare) it waits for "
                    + "approval, moves nothing, and refund is ignored.")
    @PostMapping("/purchase-returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('bill:manage', 'purchase_return:prepare')")
    public PurchaseReturnResponse create(@Valid @RequestBody PurchaseReturnRequest req) {
        return returns.create(req);
    }

    @GetMapping("/purchase-returns")
    @PreAuthorize("hasAnyAuthority('bill:manage', 'purchase_return:prepare')")
    public PageResponse<PurchaseReturnResponse> list(@RequestParam(required = false) Long supplierId,
                                                     @RequestParam(required = false) PurchaseReturn.Status status,
                                                     @ParameterObject Pageable pageable) {
        return returns.list(supplierId, status, pageable);
    }

    @GetMapping("/purchase-returns/{id}")
    @PreAuthorize("hasAnyAuthority('bill:manage', 'purchase_return:prepare')")
    public PurchaseReturnResponse get(@PathVariable Long id) {
        return returns.get(id);
    }

    @Operation(summary = "Post a prepared return", description = "The goods leave stock now and the supplier credit is used on "
            + "the bill first; the rest stays as credit. 422 RETURN_RECEIPT_VOID when the receipt was voided meanwhile.")
    @PostMapping("/purchase-returns/{id}/approve")
    @PreAuthorize("hasAuthority('bill:manage')")
    public PurchaseReturnResponse approve(@PathVariable Long id) {
        return returns.approve(id);
    }

    @Operation(summary = "Reject a prepared return", description = "Nothing moves; the goods can be prepared again.")
    @PostMapping("/purchase-returns/{id}/reject")
    @PreAuthorize("hasAuthority('bill:manage')")
    public PurchaseReturnResponse reject(@PathVariable Long id) {
        return returns.reject(id);
    }

    @Operation(summary = "Void a posted purchase return", description = "The goods come back into the batches they left and the "
            + "supplier credit is voided; credit used on bills is taken off them. 422 VOID_CREDIT_REFUNDED when the supplier "
            + "refunded it.")
    @PostMapping("/purchase-returns/{id}/void")
    @PreAuthorize("hasAuthority('bill:manage')")
    public PurchaseReturnResponse voidReturn(@PathVariable Long id) {
        return voids.voidReturn(id);
    }

    @Operation(summary = "Void a supplier credit written by hand", description = "Credit used on bills is taken off them. 422 "
            + "VOID_CREDIT_FROM_RETURN for one made by a return (void the return), VOID_CREDIT_REFUNDED when refunded.")
    @PostMapping("/supplier-credits/{id}/void")
    @PreAuthorize("hasAuthority('bill:manage')")
    public SupplierCreditResponse voidCredit(@PathVariable Long id) {
        return voids.voidCredit(id);
    }

    @Operation(summary = "A supplier credit by hand", description = "For a price correction; used on bills or refunded later.")
    @PostMapping("/supplier-credits")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('bill:manage')")
    public SupplierCreditResponse createCredit(@Valid @RequestBody SupplierCreditRequest req) {
        return returns.createCredit(req);
    }

    @GetMapping("/supplier-credits")
    @PreAuthorize("hasAuthority('bill:manage')")
    public PageResponse<SupplierCreditResponse> credits(@RequestParam(required = false) Long supplierId,
                                                        @RequestParam(required = false) SupplierCredit.Status status,
                                                        @ParameterObject Pageable pageable) {
        return returns.credits(supplierId, status, pageable);
    }

    @GetMapping("/supplier-credits/{id}")
    @PreAuthorize("hasAuthority('bill:manage')")
    public SupplierCreditResponse credit(@PathVariable Long id) {
        return returns.credit(id);
    }

    @Operation(summary = "Use a supplier credit on a bill of the same supplier")
    @PostMapping("/supplier-credits/{id}/apply")
    @PreAuthorize("hasAuthority('bill:manage')")
    public SupplierCreditResponse applyCredit(@PathVariable Long id, @Valid @RequestBody SupplierCreditApplyRequest req) {
        return returns.applyCredit(id, req);
    }

    @Operation(summary = "The supplier pays the credit back")
    @PostMapping("/supplier-credits/{id}/refund")
    @PreAuthorize("hasAuthority('bill:manage')")
    public SupplierCreditResponse refundCredit(@PathVariable Long id, @Valid @RequestBody SupplierCreditRefundRequest req) {
        return returns.refundCredit(id, req);
    }
}
