package com.modernstock.sale;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BusinessContext;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.PageResponse;
import com.modernstock.sale.QuotationService.QuotationRequest;
import com.modernstock.sale.QuotationService.QuotationResponse;
import com.modernstock.sale.SaleLines.Preview;
import com.modernstock.sale.SaleLines.PreviewRequest;
import com.modernstock.sale.SalesOrderService.DeliverRequest;
import com.modernstock.sale.SalesOrderService.DeliverResponse;
import com.modernstock.sale.SalesOrderService.QuickOrderRequest;
import com.modernstock.sale.SalesOrderService.SalesOrderRequest;
import com.modernstock.sale.SalesOrderService.SalesOrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Quotations and sales orders (FR-3.10, FR-3.11): Owners, Managers and Cashiers make them (sales_order:manage); the
 * people who hand goods over deliver them (delivery:manage); a Stock keeper can see them.
 */
@Tag(name = "Quotations and sales orders")
@RestController
@RequiredArgsConstructor
public class OrderController {

    private static final String VIEW = "hasAnyAuthority('sales_order:view', 'sales_order:manage', 'delivery:manage')";

    private final QuotationService quotations;
    private final SalesOrderService orders;
    private final SalePricing pricing;
    private final ProductService products;

    @Operation(summary = "Prices and totals of lines before saving",
            description = "The same prices, discount limit and VAT as the POS, quotations and orders; overLimit says the "
                    + "discount needs an Owner or Manager.")
    @PostMapping("/sales/price")
    @PreAuthorize("hasAnyAuthority('sales_order:manage', 'sale:create')")
    @Transactional(readOnly = true)
    public Preview price(@Valid @RequestBody PreviewRequest req) {
        Long branchId = BusinessContext.branchForWrite(req.branchId());
        return SaleLines.preview(pricing.price(branchId, SaleLines.toPricing(req.lines()), req.discount(), false),
                products.unitSymbols());
    }

    // ---------------------------------------------------------------- quotations

    @GetMapping("/quotations")
    @PreAuthorize(VIEW)
    public PageResponse<QuotationResponse> quotations(@RequestParam(required = false) Long customerId,
                                                      @RequestParam(required = false) Quotation.Status status,
                                                      @ParameterObject Pageable pageable) {
        return quotations.list(customerId, status, pageable);
    }

    @GetMapping("/quotations/{id}")
    @PreAuthorize(VIEW)
    public QuotationResponse quotation(@PathVariable Long id) {
        return quotations.get(id);
    }

    @Operation(summary = "A price offer", description = "Today's prices for the branch; valid 14 days unless validUntil is given.")
    @PostMapping("/quotations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public QuotationResponse createQuotation(@Valid @RequestBody QuotationRequest req) {
        return quotations.create(req);
    }

    @PutMapping("/quotations/{id}")
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public QuotationResponse updateQuotation(@PathVariable Long id, @Valid @RequestBody QuotationRequest req) {
        return quotations.update(id, req);
    }

    @DeleteMapping("/quotations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public void deleteQuotation(@PathVariable Long id) {
        quotations.delete(id);
    }

    @PostMapping("/quotations/{id}/send")
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public QuotationResponse sendQuotation(@PathVariable Long id) {
        return quotations.send(id);
    }

    @PostMapping("/quotations/{id}/decline")
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public QuotationResponse declineQuotation(@PathVariable Long id) {
        return quotations.decline(id);
    }

    @Operation(summary = "Accepted: make it a sales order", description = "At the quoted prices. 422 QUOTATION_EXPIRED after its date.")
    @PostMapping("/quotations/{id}/convert")
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public SalesOrderResponse convertQuotation(@PathVariable Long id) {
        return quotations.convert(id);
    }

    // ---------------------------------------------------------------- sales orders

    @GetMapping("/sales-orders")
    @PreAuthorize(VIEW)
    public PageResponse<SalesOrderResponse> salesOrders(@RequestParam(required = false) Long customerId,
                                                        @RequestParam(required = false) SalesOrder.Status status,
                                                        @ParameterObject Pageable pageable) {
        return orders.list(customerId, status, pageable);
    }

    @GetMapping("/sales-orders/{id}")
    @PreAuthorize(VIEW)
    public SalesOrderResponse salesOrder(@PathVariable Long id) {
        return orders.get(id);
    }

    @Operation(summary = "A confirmed order", description = "Needs a customer with a phone: each delivery makes an invoice.")
    @PostMapping("/sales-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public SalesOrderResponse createSalesOrder(@Valid @RequestBody SalesOrderRequest req) {
        return orders.create(req);
    }

    @PutMapping("/sales-orders/{id}")
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public SalesOrderResponse updateSalesOrder(@PathVariable Long id, @Valid @RequestBody SalesOrderRequest req) {
        return orders.update(id, req);
    }

    @PostMapping("/sales-orders/{id}/cancel")
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public SalesOrderResponse cancelSalesOrder(@PathVariable Long id) {
        return orders.cancel(id);
    }

    @PostMapping("/sales-orders/{id}/close")
    @PreAuthorize("hasAuthority('sales_order:manage')")
    public SalesOrderResponse closeSalesOrder(@PathVariable Long id) {
        return orders.close(id);
    }

    @Operation(summary = "Deliver all or part of an order",
            description = "Stock leaves the order's warehouse (FEFO) and the delivery's invoice is made at the ordered prices. "
                    + "422 DELIVER_TOO_MANY, CREDIT_LIMIT_EXCEEDED.")
    @PostMapping("/sales-orders/{id}/deliver")
    @PreAuthorize("hasAuthority('delivery:manage')")
    public DeliverResponse deliver(@PathVariable Long id, @Valid @RequestBody DeliverRequest req) {
        return orders.deliver(id, req);
    }

    @Operation(summary = "Save, deliver and invoice now", description = "The order, its delivery and invoice, and any money paid.")
    @PostMapping("/sales-orders/quick")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('sales_order:manage') and hasAuthority('delivery:manage')")
    public DeliverResponse quick(@Valid @RequestBody QuickOrderRequest req) {
        return orders.quick(req);
    }
}
