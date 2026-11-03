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
import com.modernstock.money.PaymentResponse;
import com.modernstock.sale.CashShiftService.CashMovementRequest;
import com.modernstock.sale.CashShiftService.CashShiftResponse;
import com.modernstock.sale.CashShiftService.CloseShiftRequest;
import com.modernstock.sale.CashShiftService.OpenShiftRequest;
import com.modernstock.sale.PosService.CheckoutRequest;
import com.modernstock.sale.PosService.CheckoutResponse;
import com.modernstock.sale.PosService.DeliveryResponse;
import com.modernstock.sale.PosService.InvoiceResponse;
import com.modernstock.sale.PosService.ReceivePaymentRequest;
import com.modernstock.sale.PosService.SalesReceiptResponse;
import com.modernstock.sale.PosService.VoidRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SalesController {

    private final PosService pos;
    private final CashShiftService cashShifts;

    // ------------------------------------------------------------------ POS

    @Tag(name = "POS")
    @Operation(summary = "Checkout",
            description = """
                    Paid in full → sales receipt. Not paid in full → delivery + invoice (named customer with a phone,
                    credit limit checked). Stock is taken earliest expiry first; not enough stock gives 422
                    INSUFFICIENT_STOCK and nothing is saved. Payments are compared in riel; change is given in riel,
                    rounded down to the business's riel step. Example at 4,100: total $12.35 = ៛50,600; paying
                    $10 + ៛10,000 = ៛51,000 gives ៛400 change. If the cashier has an open cash shift in the branch,
                    cash goes into it.""")
    @PostMapping("/pos/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('sale:create')")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest req) {
        return pos.checkout(req);
    }

    @Tag(name = "POS")
    @Operation(summary = "Sales receipts", description = "Cashiers see only their own sales.")
    @GetMapping("/sales-receipts")
    @PreAuthorize("hasAuthority('sale:create')")
    public PageResponse<SalesReceiptResponse> receipts(@RequestParam(required = false) SalesReceipt.Status status,
                                                       @ParameterObject Pageable pageable) {
        return pos.receipts(status, pageable);
    }

    @Tag(name = "POS")
    @GetMapping("/sales-receipts/{id}")
    @PreAuthorize("hasAuthority('sale:create')")
    public SalesReceiptResponse receipt(@PathVariable Long id) {
        return pos.receipt(id);
    }

    @Tag(name = "POS")
    @Operation(summary = "Void a sale", description = "Owner or Manager. Stock goes back into the same batches and the payments are voided.")
    @PostMapping("/sales-receipts/{id}/void")
    @PreAuthorize("hasAuthority('sale:void')")
    public SalesReceiptResponse voidReceipt(@PathVariable Long id, @Valid @RequestBody VoidRequest req) {
        return pos.voidReceipt(id, req);
    }

    @Tag(name = "POS")
    @GetMapping("/invoices")
    @PreAuthorize("hasAnyAuthority('sale:create', 'customer:view')")
    public PageResponse<InvoiceResponse> invoices(@RequestParam(required = false) Long customerId,
                                                  @RequestParam(required = false) Invoice.Status status,
                                                  @ParameterObject Pageable pageable) {
        return pos.invoices(customerId, status, pageable);
    }

    @Tag(name = "POS")
    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAnyAuthority('sale:create', 'customer:view')")
    public InvoiceResponse invoice(@PathVariable Long id) {
        return pos.invoice(id);
    }

    @Tag(name = "POS")
    @GetMapping("/deliveries")
    @PreAuthorize("hasAnyAuthority('delivery:manage', 'sale:create')")
    public PageResponse<DeliveryResponse> deliveries(@RequestParam(required = false) Long customerId,
                                                     @ParameterObject Pageable pageable) {
        return pos.deliveries(customerId, pageable);
    }

    @Tag(name = "POS")
    @GetMapping("/deliveries/{id}")
    @PreAuthorize("hasAnyAuthority('delivery:manage', 'sale:create')")
    public DeliveryResponse delivery(@PathVariable Long id) {
        return pos.delivery(id);
    }

    @Tag(name = "POS")
    @Operation(summary = "Receive a payment from a customer", description = "Pays the listed invoices, or the oldest due first. The rest is a deposit.")
    @PostMapping("/payments-received")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('sale:create')")
    public PaymentResponse receivePayment(@Valid @RequestBody ReceivePaymentRequest req) {
        return pos.receivePayment(req);
    }

    @Tag(name = "POS")
    @GetMapping("/payments-received")
    @PreAuthorize("hasAuthority('sale:create')")
    public PageResponse<PaymentResponse> paymentsReceived(@RequestParam(required = false) Long customerId,
                                                          @ParameterObject Pageable pageable) {
        return pos.paymentsReceived(customerId, pageable);
    }

    // ------------------------------------------------------------------ cash shifts

    @Tag(name = "Cash shifts")
    @Operation(summary = "Open my cash shift", description = "One open shift per cashier and branch.")
    @PostMapping("/cash-shifts/open")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('cash_shift:own', 'cash_shift:all')")
    public CashShiftResponse open(@Valid @RequestBody OpenShiftRequest req) {
        return cashShifts.open(req);
    }

    @Tag(name = "Cash shifts")
    @Operation(summary = "My open shift in a branch", description = "404 when no shift is open.")
    @GetMapping("/cash-shifts/current")
    @PreAuthorize("hasAnyAuthority('cash_shift:own', 'cash_shift:all')")
    public CashShiftResponse current(@Parameter(description = "Defaults to the selected branch")
                                     @RequestParam(required = false) Long branchId) {
        return cashShifts.current(branchId);
    }

    @Tag(name = "Cash shifts")
    @Operation(summary = "Shifts", description = "Cashiers see their own; cash_shift:all sees all, with differences.")
    @GetMapping("/cash-shifts")
    @PreAuthorize("hasAnyAuthority('cash_shift:own', 'cash_shift:all')")
    public PageResponse<CashShiftResponse> shifts(@ParameterObject Pageable pageable) {
        return cashShifts.list(pageable);
    }

    @Tag(name = "Cash shifts")
    @GetMapping("/cash-shifts/{id}")
    @PreAuthorize("hasAnyAuthority('cash_shift:own', 'cash_shift:all')")
    public CashShiftResponse shift(@PathVariable Long id) {
        return cashShifts.get(id);
    }

    @Tag(name = "Cash shifts")
    @Operation(summary = "Add or take cash out of the drawer")
    @PostMapping("/cash-shifts/{id}/cash-movements")
    @PreAuthorize("hasAnyAuthority('cash_shift:own', 'cash_shift:all')")
    public CashShiftResponse cashMovement(@PathVariable Long id, @Valid @RequestBody CashMovementRequest req) {
        return cashShifts.addMovement(id, req);
    }

    @Tag(name = "Cash shifts")
    @Operation(summary = "Close a shift",
            description = "Count dollars and riel; the answer shows expected, counted and the difference (+ over, − short) per currency.")
    @PostMapping("/cash-shifts/{id}/close")
    @PreAuthorize("hasAnyAuthority('cash_shift:own', 'cash_shift:all')")
    public CashShiftResponse close(@PathVariable Long id, @Valid @RequestBody CloseShiftRequest req) {
        return cashShifts.close(id, req);
    }
}
