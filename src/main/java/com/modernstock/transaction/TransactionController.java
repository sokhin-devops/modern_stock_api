package com.modernstock.transaction;

import java.time.LocalDate;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.PageResponse;
import com.modernstock.money.Allocation;
import com.modernstock.money.AllocationRepository;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentRepository;
import com.modernstock.money.PaymentResponse;
import com.modernstock.partner.CustomerRepository;
import com.modernstock.partner.SupplierRepository;
import com.modernstock.report.MoneyReportService;
import com.modernstock.report.MoneyReportService.Party;
import com.modernstock.report.MoneyReportService.Statement;
import com.modernstock.report.ReportService;
import com.modernstock.transaction.TransactionService.PartySummary;
import com.modernstock.transaction.TransactionService.Side;
import com.modernstock.transaction.TransactionService.TransactionRow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The Transactions screens and the customer and supplier pages (PLAIN.md 12.2): every document of a side in one list,
 * one party's key amounts, and its statement.
 */
@Tag(name = "Transactions")
@RestController
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactions;
    private final MoneyReportService money;
    private final CustomerRepository customers;
    private final SupplierRepository suppliers;
    private final PaymentRepository payments;
    private final AllocationRepository allocations;

    @Operation(summary = "Every document of one side, newest first",
            description = "Only the kinds the member may open, from the branches they see. view narrows it to one kind: customers "
                    + "quotations, sales-orders, deliveries, invoices, sales-receipts, payments, returns, credits; suppliers "
                    + "purchase-orders, goods-receipts, bills, payments, returns, credits. q matches the number or the party's name; "
                    + "from/to are document dates. Payments taken at the POS for a sale paid in full are part of that sale.")
    @GetMapping("/transactions")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<TransactionRow> transactions(@RequestParam Side side, @RequestParam(required = false) String view,
                                             @RequestParam(required = false) Long partyId, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String q, @RequestParam(required = false) Long branchId,
                                             @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return transactions.list(side, view, partyId, status, q, branchId, from, to, page, size);
    }

    @Operation(summary = "The kinds of documents this member sees on a side", description = "The values of ?view= for /transactions.")
    @GetMapping("/transactions/views")
    @PreAuthorize("isAuthenticated()")
    public List<String> views(@RequestParam Side side) {
        return transactions.views(side);
    }

    @Tag(name = "Customers")
    @Operation(summary = "A customer's key amounts", description = "What they owe, the overdue part, sales this year, and the balance per branch.")
    @GetMapping("/customers/{id}/summary")
    @PreAuthorize("hasAuthority('customer:view')")
    public PartySummary customerSummary(@PathVariable Long id) {
        customers.get(id);
        return transactions.summary(Side.CUSTOMER, id);
    }

    @Tag(name = "Suppliers")
    @Operation(summary = "A supplier's key amounts", description = "What is owed to them, the overdue part, bills this year, and per branch.")
    @GetMapping("/suppliers/{id}/summary")
    @PreAuthorize("hasAuthority('supplier:view')")
    public PartySummary supplierSummary(@PathVariable Long id) {
        suppliers.get(id);
        return transactions.summary(Side.SUPPLIER, id);
    }

    @Tag(name = "Customers")
    @Operation(summary = "A customer's statement", description = "Like /reports/statement, for whoever sees customers. Default: the last 90 days.")
    @GetMapping("/customers/{id}/statement")
    @PreAuthorize("hasAuthority('customer:view')")
    public Statement customerStatement(@PathVariable Long id, @RequestParam(required = false) LocalDate from,
                                       @RequestParam(required = false) LocalDate to) {
        customers.get(id);
        LocalDate end = to == null ? ReportService.today() : to;
        return money.statementOf(Party.CUSTOMER, id, from == null ? end.minusDays(89) : from, end);
    }

    @Tag(name = "Suppliers")
    @Operation(summary = "A supplier's statement", description = "Like /reports/statement, for whoever sees suppliers. Default: the last 90 days.")
    @GetMapping("/suppliers/{id}/statement")
    @PreAuthorize("hasAuthority('supplier:view')")
    public Statement supplierStatement(@PathVariable Long id, @RequestParam(required = false) LocalDate from,
                                       @RequestParam(required = false) LocalDate to) {
        suppliers.get(id);
        LocalDate end = to == null ? ReportService.today() : to;
        return money.statementOf(Party.SUPPLIER, id, from == null ? end.minusDays(89) : from, end);
    }

    @Operation(summary = "One payment", description = "Received from a customer (sale:create) or paid to a supplier (bill:manage), "
            + "with what it paid.")
    @GetMapping("/payments/{id}")
    @PreAuthorize("hasAnyAuthority('sale:create', 'bill:manage')")
    @Transactional(readOnly = true)
    public PaymentResponse payment(@Parameter(description = "Payment ID") @PathVariable Long id) {
        Payment p = payments.get(id);
        boolean supplier = p.getPartyType() == Payment.PartyType.SUPPLIER;
        if (!BusinessContext.has(supplier ? "bill:manage" : "sale:create")) {
            throw ApiException.forbidden("PERMISSION_DENIED");
        }
        BusinessContext.requireBranch(p.getBranchId());
        String name = p.getPartyId() == null ? null : supplier
                ? suppliers.findById(p.getPartyId()).map(s -> s.getName()).orElse(null)
                : customers.findById(p.getPartyId()).map(c -> c.getName()).orElse(null);
        return PaymentResponse.from(p, allocations.findByFromTypeAndFromId(Allocation.FromType.PAYMENT, p.getId()), name);
    }
}
