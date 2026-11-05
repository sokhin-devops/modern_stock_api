package com.modernstock.sale;

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
import com.modernstock.sale.ReturnService.ApplyRequest;
import com.modernstock.sale.ReturnService.CreditNoteRequest;
import com.modernstock.sale.ReturnService.CreditNoteResponse;
import com.modernstock.sale.ReturnService.CreditRefundRequest;
import com.modernstock.sale.ReturnService.ReturnRequest;
import com.modernstock.sale.ReturnService.ReturnResponse;
import com.modernstock.sale.ReturnService.Returnable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Sales returns and credit notes (FR-3.12). Taking goods back and giving money back is for Owners and Managers
 * (sale:void, like voiding a sale).
 */
@Tag(name = "Returns and credit notes")
@RestController
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnService returns;

    @Operation(summary = "What can still be returned from a sales receipt")
    @GetMapping("/sales-receipts/{id}/returnable")
    @PreAuthorize("hasAnyAuthority('sale:void', 'sales_return:request')")
    public Returnable returnableFromReceipt(@PathVariable Long id) {
        return returns.returnable(id, null);
    }

    @Operation(summary = "What can still be returned from an invoice")
    @GetMapping("/invoices/{id}/returnable")
    @PreAuthorize("hasAnyAuthority('sale:void', 'sales_return:request')")
    public Returnable returnableFromInvoice(@PathVariable Long id) {
        return returns.returnable(null, id);
    }

    @Operation(summary = "Take goods back",
            description = "From salesReceiptId or invoiceId. Good items go back to stock; damaged ones are a loss. A credit note "
                    + "is made for the value: used on the invoice first, then refunded (refund) or kept as credit. A walk-in "
                    + "customer must be refunded. 422 RETURN_TOO_MANY when more than was sold and not yet returned (or waiting). "
                    + "Without sale:void (a Cashier with sales_return:request) it waits for approval and moves nothing.")
    @PostMapping("/sales-returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('sale:void', 'sales_return:request')")
    public ReturnResponse createSalesReturn(@Valid @RequestBody ReturnRequest req) {
        return returns.create(req);
    }

    @GetMapping("/sales-returns")
    @PreAuthorize("hasAnyAuthority('sale:void', 'customer:view', 'sales_return:request')")
    public PageResponse<ReturnResponse> salesReturns(@RequestParam(required = false) SalesReturn.Status status,
                                                     @ParameterObject Pageable pageable) {
        return returns.list(status, pageable);
    }

    @GetMapping("/sales-returns/{id}")
    @PreAuthorize("hasAnyAuthority('sale:void', 'customer:view', 'sales_return:request')")
    public ReturnResponse salesReturn(@PathVariable Long id) {
        return returns.get(id);
    }

    @Operation(summary = "Approve a requested return", description = "Posted now: goods back, credit note, and the refund the "
            + "Cashier asked for (cash from their open shift). 422 RETURN_SALE_VOID when the sale was voided meanwhile.")
    @PostMapping("/sales-returns/{id}/approve")
    @PreAuthorize("hasAuthority('sale:void')")
    public ReturnResponse approveSalesReturn(@PathVariable Long id) {
        return returns.approve(id);
    }

    @Operation(summary = "Reject a requested return", description = "Nothing moves; the goods can be asked for again.")
    @PostMapping("/sales-returns/{id}/reject")
    @PreAuthorize("hasAuthority('sale:void')")
    public ReturnResponse rejectSalesReturn(@PathVariable Long id) {
        return returns.reject(id);
    }

    @Operation(summary = "A credit note by hand", description = "For a price correction; used on invoices or refunded later.")
    @PostMapping("/credit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('sale:void')")
    public CreditNoteResponse createCreditNote(@Valid @RequestBody CreditNoteRequest req) {
        return returns.createCreditNote(req);
    }

    @GetMapping("/credit-notes")
    @PreAuthorize("hasAnyAuthority('sale:void', 'customer:view')")
    public PageResponse<CreditNoteResponse> creditNotes(@RequestParam(required = false) Long customerId,
                                                        @RequestParam(required = false) CreditNote.Status status,
                                                        @ParameterObject Pageable pageable) {
        return returns.creditNotes(customerId, status, pageable);
    }

    @GetMapping("/credit-notes/{id}")
    @PreAuthorize("hasAnyAuthority('sale:void', 'customer:view')")
    public CreditNoteResponse creditNote(@PathVariable Long id) {
        return returns.creditNote(id);
    }

    @Operation(summary = "Use a credit note on an invoice of the same customer")
    @PostMapping("/credit-notes/{id}/apply")
    @PreAuthorize("hasAuthority('sale:void')")
    public CreditNoteResponse applyCreditNote(@PathVariable Long id, @Valid @RequestBody ApplyRequest req) {
        return returns.applyCreditNote(id, req);
    }

    @Operation(summary = "Give the money back", description = "Cash on the cashier's open shift lowers its expected cash.")
    @PostMapping("/credit-notes/{id}/refund")
    @PreAuthorize("hasAuthority('sale:void')")
    public CreditNoteResponse refundCreditNote(@PathVariable Long id, @Valid @RequestBody CreditRefundRequest req) {
        return returns.refundCreditNote(id, req);
    }
}
