package com.modernstock.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.inventory.StockService;
import com.modernstock.money.Allocation;
import com.modernstock.money.AllocationRepository;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentRepository;
import com.modernstock.money.PaymentResponse;
import com.modernstock.partner.CustomerRepository;
import com.modernstock.sale.PosService.InvoiceResponse;
import com.modernstock.sale.ReturnService.CreditNoteResponse;
import com.modernstock.sale.ReturnService.ReturnResponse;

import lombok.RequiredArgsConstructor;

/**
 * Voids on the selling side (PLAIN.md 11.1 rule 3, 11.8): the document stays, marked void, and its stock and money
 * changes are reversed in the same transaction. Refused while something depends on it: an invoice with payments or
 * returns, a credit note that was refunded in cash, goods from a return that were sold again. A POS sale paid in full is
 * voided as a sale (with its payments). Only those who may void sales (Owner, Manager) may void.
 */
@Service
@RequiredArgsConstructor
public class SaleVoids {

    private final InvoiceRepository invoices;
    private final DeliveryRepository deliveries;
    private final SaleItemRepository saleItems;
    private final SalesOrderRepository orders;
    private final SalesReturnRepository returns;
    private final CreditNoteRepository creditNotes;
    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final CustomerRepository customers;
    private final StockService stock;
    private final ActivityService activity;
    private final PosService pos;
    private final ReturnService returnService;

    /**
     * An invoice and the delivery it was made from: the goods go back into the batches they left, and a sales order
     * counts them as not delivered. Debit notes are voided on their own (they have no goods).
     */
    @Transactional
    public InvoiceResponse voidInvoice(Long id) {
        Invoice inv = invoices.lock(id);
        BusinessContext.requireBranch(inv.getBranchId());
        if (inv.getType() == Invoice.Type.DEBIT_NOTE) {
            throw ApiException.rule("VOID_USE_DEBIT_NOTE");
        }
        if (inv.getStatus() == Invoice.Status.VOID) {
            throw ApiException.rule("WRONG_STATUS", inv.getStatus());
        }
        if (inv.getPaidAmount().signum() != 0) {
            throw ApiException.rule("VOID_INVOICE_PAID");
        }
        if (returns.existsByInvoiceIdAndStatusIn(inv.getId(),
                EnumSet.of(SalesReturn.Status.POSTED, SalesReturn.Status.WAITING_APPROVAL))) {
            throw ApiException.rule("VOID_HAS_RETURNS");
        }
        if (invoices.existsByRelatedInvoiceIdAndStatusNot(inv.getId(), Invoice.Status.VOID)) {
            throw ApiException.rule("VOID_HAS_DEBIT_NOTES");
        }
        Instant now = Instant.now();
        if (inv.getDeliveryId() != null) {
            Delivery d = deliveries.lock(inv.getDeliveryId());
            if (d.getStatus() == Delivery.Status.POSTED) {
                stock.reverse(new StockService.Ref(PosService.DELIVERY_REF, d.getId()));
                if (d.getSalesOrderId() != null) {
                    undeliver(d);
                }
                d.setStatus(Delivery.Status.VOID);
                d.setVoidedAt(now);
                d.setVoidedBy(CurrentUser.id());
            }
        }
        inv.setStatus(Invoice.Status.VOID);
        inv.setVoidedAt(now);
        inv.setVoidedBy(CurrentUser.id());
        activity.record("INVOICE_VOIDED", "CUSTOMER", inv.getCustomerId(), inv.getNumber() + " · " + customerName(inv.getCustomerId()),
                inv.getBranchId(), ActivityService.values("total", inv.getTotal()), null);
        return pos.invoice(inv.getId());
    }

    /** The order's lines count what this delivery took as still to deliver. */
    private void undeliver(Delivery d) {
        SalesOrder o = orders.lock(d.getSalesOrderId());
        for (SaleItem si : saleItems.findByDeliveryIdOrderById(d.getId())) {
            o.getItems().stream().filter(i -> i.getId().equals(si.getSalesOrderItemId())).findFirst()
                    .ifPresent(i -> i.setQtyDelivered(i.getQtyDelivered().subtract(si.getQty()).max(BigDecimal.ZERO)));
        }
        if (o.getStatus() == SalesOrder.Status.DELIVERED || o.getStatus() == SalesOrder.Status.PARTIALLY_DELIVERED) {
            boolean any = o.getItems().stream().anyMatch(i -> i.getQtyDelivered().signum() > 0);
            o.setStatus(any ? SalesOrder.Status.PARTIALLY_DELIVERED : SalesOrder.Status.CONFIRMED);
        }
    }

    /**
     * A payment received by mistake: the invoices it paid are open again by the same amounts; cash leaves the open
     * shift's expected cash. Money taken at the POS for a sale paid in full goes with the sale (void the sale).
     */
    @Transactional
    public PaymentResponse voidPayment(Long id) {
        Payment p = payments.lock(id);
        BusinessContext.requireBranch(p.getBranchId());
        if (p.getPartyType() == Payment.PartyType.SUPPLIER) {
            throw ApiException.notFound("Payment");
        }
        if (p.getDirection() != Payment.Direction.IN) {
            throw ApiException.rule("VOID_PAYMENT_IS_REFUND");
        }
        if (p.getStatus() != Payment.Status.POSTED) {
            throw ApiException.rule("WRONG_STATUS", p.getStatus());
        }
        List<Allocation> paid = allocations.findByFromTypeAndFromId(Allocation.FromType.PAYMENT, p.getId());
        if (paid.stream().anyMatch(a -> a.getToType() == Allocation.ToType.SALES_RECEIPT)) {
            throw ApiException.rule("VOID_PAYMENT_OF_SALE");
        }
        for (Allocation a : paid) {
            if (a.getToType() == Allocation.ToType.INVOICE) {
                invoices.lock(a.getToId()).pay(a.getAmount().negate());
            }
        }
        p.setStatus(Payment.Status.VOID);
        p.setVoidedAt(Instant.now());
        p.setVoidedBy(CurrentUser.id());
        String name = customerName(p.getPartyId());
        activity.record("PAYMENT_RECEIVED_VOIDED", "CUSTOMER", p.getPartyId(), p.getNumber() + (name.isEmpty() ? "" : " · " + name),
                p.getBranchId(), ActivityService.values("amount", p.getAmountUsd()), null);
        return PaymentResponse.from(p, paid, name.isEmpty() ? null : name);
    }

    /**
     * A sales return: good items leave stock again (while they are still there), damaged ones stop counting as a loss,
     * and its credit note is void. Refused when the credit note was refunded; credit used on invoices is taken off them.
     */
    @Transactional
    public ReturnResponse voidReturn(Long id) {
        SalesReturn r = returns.lock(id);
        BusinessContext.requireBranch(r.getBranchId());
        if (r.getStatus() != SalesReturn.Status.POSTED) {
            throw ApiException.rule("WRONG_STATUS", r.getStatus());
        }
        Instant now = Instant.now();
        for (CreditNote cn : creditNotes.findBySalesReturnId(r.getId())) {
            undoCredit(cn, now);
        }
        StockService.Ref ref = new StockService.Ref(ReturnService.REF, r.getId());
        stock.requireStillThere(ref);
        stock.reverse(ref);
        r.setStatus(SalesReturn.Status.VOID);
        r.setVoidedAt(now);
        r.setVoidedBy(CurrentUser.id());
        activity.record("SALES_RETURN_VOIDED", "SALES_RETURN", r.getId(), r.getNumber(), r.getBranchId(),
                ActivityService.values("total", r.getTotal()), null);
        return returnService.get(r.getId());
    }

    /** A credit note written by hand (a price correction). One from a return goes with its return. */
    @Transactional
    public CreditNoteResponse voidCreditNote(Long id) {
        CreditNote cn = creditNotes.lock(id);
        BusinessContext.requireBranch(cn.getBranchId());
        if (cn.getSalesReturnId() != null) {
            throw ApiException.rule("VOID_CREDIT_FROM_RETURN");
        }
        undoCredit(cn, Instant.now());
        activity.record("CREDIT_NOTE_VOIDED", "CUSTOMER", cn.getCustomerId(),
                cn.getNumber() + (cn.getCustomerId() == null ? "" : " · " + customerName(cn.getCustomerId())), cn.getBranchId(),
                ActivityService.values("amount", cn.getAmount()), null);
        return returnService.creditNote(cn.getId());
    }

    private void undoCredit(CreditNote cn, Instant now) {
        if (cn.getStatus() == CreditNote.Status.VOID) {
            throw ApiException.rule("WRONG_STATUS", cn.getStatus());
        }
        if (cn.getRefundedAmount().signum() != 0) {
            throw ApiException.rule("VOID_CREDIT_REFUNDED", cn.getNumber());
        }
        for (Allocation a : allocations.findByFromTypeAndFromId(Allocation.FromType.CREDIT_NOTE, cn.getId())) {
            if (a.getToType() == Allocation.ToType.INVOICE) {
                invoices.lock(a.getToId()).pay(a.getAmount().negate());
            }
            allocations.delete(a);
        }
        cn.setUsedAmount(BigDecimal.ZERO);
        cn.setStatus(CreditNote.Status.VOID);
        cn.setVoidedAt(now);
        cn.setVoidedBy(CurrentUser.id());
    }

    private String customerName(Long id) {
        return id == null ? "" : customers.findById(id).map(c -> c.getName()).orElse("");
    }
}
