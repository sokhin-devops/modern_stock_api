package com.modernstock.sale;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.billing.EntitlementService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.BusinessRepository;
import com.modernstock.business.SettingsService;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.currency.CurrencyService;
import com.modernstock.inventory.StockMovement;
import com.modernstock.inventory.StockService;
import com.modernstock.money.Allocation;
import com.modernstock.money.AllocationRepository;
import com.modernstock.money.BalanceService;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentRepository;
import com.modernstock.money.PaymentService;
import com.modernstock.partner.Customer;
import com.modernstock.sale.SalePricing.PricedLine;

import lombok.RequiredArgsConstructor;

/**
 * Deliveries and their invoices, the same from the POS (pay later) and from sales orders (PLAIN.md 11.8): pay later needs
 * a named customer with a phone, within the credit limit unless sale:override; stock leaves by FEFO; the invoice is
 * created from the delivery: a tax invoice, with its own numbers, when a VAT-registered business sells to a customer with
 * a VAT TIN (FR-3.9), otherwise a standard invoice.
 */
@Component
@RequiredArgsConstructor
public class SaleDocuments {

    private final DeliveryRepository deliveries;
    private final InvoiceRepository invoices;
    private final SaleItemRepository saleItems;
    private final StockService stock;
    private final CurrencyService currency;
    private final BalanceService balances;
    private final DocumentNumberService numbers;
    private final SettingsService settings;
    private final BusinessRepository businesses;
    private final EntitlementService entitlements;
    private final PaymentRepository paymentRecords;
    private final AllocationRepository allocations;
    private final PaymentService payments;

    /** 422 CUSTOMER_REQUIRED_FOR_CREDIT: only a named customer with a phone may owe money. */
    public void requireCreditCustomer(Customer customer) {
        if (customer == null || customer.getPhone() == null || customer.getPhone().isBlank()) {
            throw ApiException.rule("CUSTOMER_REQUIRED_FOR_CREDIT");
        }
    }

    /** 422 CUSTOMER_REQUIRED_FOR_CREDIT or CREDIT_LIMIT_EXCEEDED when the customer can't owe `due` more. */
    public void checkCredit(Customer customer, BigDecimal due) {
        requireCreditCustomer(customer);
        if (customer.getCreditLimit().signum() > 0
                && balances.customer(customer.getId()).add(due).compareTo(customer.getCreditLimit()) > 0
                && !BusinessContext.has("sale:override")) {
            throw ApiException.rule("CREDIT_LIMIT_EXCEEDED", customer.getCreditLimit().toPlainString());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Delivery newDelivery(Long branchId, Long warehouseId, Long customerId, Long salesOrderId, String note) {
        Instant now = Instant.now();
        Delivery d = new Delivery();
        d.setNumber(numbers.next(DocType.DO));
        d.setBranchId(branchId);
        d.setWarehouseId(warehouseId);
        d.setCustomerId(customerId);
        d.setSalesOrderId(salesOrderId);
        d.setDeliveredAt(now);
        d.setStatus(Delivery.Status.POSTED);
        d.setPostedAt(now);
        d.setNote(note);
        return deliveries.save(d);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Invoice newInvoice(Delivery delivery, Customer customer, BigDecimal rate, BigDecimal subtotal, BigDecimal discount,
                              BigDecimal vat, BigDecimal total, String note) {
        Invoice invoice = new Invoice();
        invoice.setType(typeFor(customer));
        invoice.setNumber(numbers.next(invoice.getType() == Invoice.Type.TAX ? DocType.TAX : DocType.INV));
        invoice.setBranchId(delivery.getBranchId());
        invoice.setDeliveryId(delivery.getId());
        invoice.setCustomerId(customer.getId());
        invoice.setInvoiceDate(Dates.today());
        invoice.setDueDate(Dates.today().plusDays(customer.getPaymentTermsDays()));
        invoice.setExchangeRate(rate);
        invoice.setSubtotal(subtotal);
        invoice.setDiscount(discount);
        invoice.setVat(vat);
        invoice.setTotal(total);
        invoice.setTotalKhr(currency.toKhr(total, rate));
        invoice.setStatus(total.signum() == 0 ? Invoice.Status.PAID : Invoice.Status.UNPAID);
        invoice.setNote(note);
        invoices.save(invoice);
        useDeposits(invoice);
        return invoice;
    }

    /** Money the customer paid before (a deposit, FR-3.13) pays the new invoice first, oldest first (PLAIN.md 11.8). */
    private void useDeposits(Invoice invoice) {
        for (Payment p : paymentRecords.unallocated(Payment.PartyType.CUSTOMER, invoice.getCustomerId(), Payment.Direction.IN)) {
            if (invoice.open().signum() <= 0) {
                break;
            }
            BigDecimal amount = p.getAmountUsd().subtract(allocations.allocated(Allocation.FromType.PAYMENT, p.getId()))
                    .min(invoice.open());
            if (amount.signum() > 0) {
                invoice.pay(amount);
                payments.allocate(p, Allocation.ToType.INVOICE, invoice.getId(), amount);
            }
        }
    }

    /** Tax invoice when the plan has it, VAT is on, the business has its VAT TIN and the customer has one too (PLAIN.md 11.8). */
    public Invoice.Type typeFor(Customer customer) {
        if (!settings.get().vatEnabled() || customer.getVatTin() == null || customer.getVatTin().isBlank()
                || !entitlements.hasFeature(EntitlementService.TAX_INVOICE)) {
            return Invoice.Type.STANDARD;
        }
        boolean registered = businesses.findById(BusinessContext.require().businessId())
                .map(b -> b.getVatTin() != null && !b.getVatTin().isBlank()).orElse(false);
        return registered ? Invoice.Type.TAX : Invoice.Type.STANDARD;
    }

    /** Takes the line's stock (FEFO, expired batches skipped) and saves the sold line at today's average cost. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SaleItem sell(PricedLine line, Long warehouseId, StockMovement.Type type, StockService.Ref ref, Long salesReceiptId,
                         Long deliveryId, Long salesOrderItemId) {
        stock.issue(line.product(), warehouseId, line.baseQty(), type, ref, false);
        SaleItem item = new SaleItem();
        item.setSalesReceiptId(salesReceiptId);
        item.setDeliveryId(deliveryId);
        item.setSalesOrderItemId(salesOrderItemId);
        item.setProductId(line.product().getId());
        item.setUnitId(line.unitId());
        item.setQty(line.qty());
        item.setBaseQty(line.baseQty());
        item.setUnitPrice(line.price());
        item.setDiscount(line.discount());
        item.setCostAtSale(line.product().getAvgCost());
        return saleItems.save(item);
    }
}
