package com.modernstock.purchase;

import java.time.Instant;
import java.util.EnumSet;

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
import com.modernstock.partner.SupplierRepository;
import com.modernstock.purchase.PurchaseReturnService.PurchaseReturnResponse;
import com.modernstock.purchase.PurchaseReturnService.SupplierCreditResponse;
import com.modernstock.purchase.PurchaseService.GoodsReceiptResponse;

import lombok.RequiredArgsConstructor;

/**
 * Voids on the buying side (PLAIN.md 11.1 rule 3): the document stays, marked void, and its stock and money changes are
 * reversed in the same transaction. Refused while something depends on it: a bill that was paid, goods that were sent back
 * or already sold, a credit the supplier refunded. Only those who manage bills (Owner, Manager) may void.
 */
@Service
@RequiredArgsConstructor
public class PurchaseVoids {

    private final GoodsReceiptRepository receipts;
    private final BillRepository bills;
    private final PurchaseOrderRepository orders;
    private final PurchaseReturnRepository returns;
    private final SupplierCreditRepository credits;
    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final SupplierRepository suppliers;
    private final StockService stock;
    private final ActivityService activity;
    private final PurchaseService purchases;
    private final PurchaseReturnService purchaseReturns;

    /**
     * A goods receipt and the bill made from it: the goods leave the same batches (while they are still there), the
     * average cost loses them, and the order counts them as not received.
     */
    @Transactional
    public GoodsReceiptResponse voidGoodsReceipt(Long id) {
        GoodsReceipt g = receipts.lock(id);
        BusinessContext.requireBranch(g.getBranchId());
        if (g.getStatus() != GoodsReceipt.Status.POSTED) {
            throw ApiException.rule("WRONG_STATUS", g.getStatus());
        }
        if (returns.existsByGoodsReceiptIdAndStatusIn(g.getId(),
                EnumSet.of(PurchaseReturn.Status.POSTED, PurchaseReturn.Status.WAITING_APPROVAL))) {
            throw ApiException.rule("VOID_HAS_RETURNS");
        }
        Bill bill = bills.findByGoodsReceiptId(g.getId()).orElse(null);
        if (bill != null && bill.getStatus() != Bill.Status.VOID && bill.getPaidAmount().signum() != 0) {
            throw ApiException.rule("VOID_BILL_PAID", bill.getNumber());
        }
        StockService.Ref ref = new StockService.Ref(PurchaseService.GRN_REF, g.getId());
        stock.requireStillThere(ref);
        stock.reverse(ref);

        PurchaseOrder po = orders.get(g.getPurchaseOrderId());
        for (GoodsReceiptItem item : g.getItems()) {
            po.getItems().stream().filter(i -> i.getId().equals(item.getPurchaseOrderItemId())).findFirst()
                    .ifPresent(i -> i.setQtyReceived(i.getQtyReceived().subtract(item.getQty()).max(java.math.BigDecimal.ZERO)));
        }
        if (po.getStatus() == PurchaseOrder.Status.RECEIVED || po.getStatus() == PurchaseOrder.Status.PARTIALLY_RECEIVED) {
            boolean any = po.getItems().stream().anyMatch(i -> i.getQtyReceived().signum() > 0);
            po.setStatus(any ? PurchaseOrder.Status.PARTIALLY_RECEIVED : PurchaseOrder.Status.ORDERED);
        }

        Instant now = Instant.now();
        g.setStatus(GoodsReceipt.Status.VOID);
        g.setVoidedAt(now);
        g.setVoidedBy(CurrentUser.id());
        if (bill != null && bill.getStatus() != Bill.Status.VOID) {
            bill.setStatus(Bill.Status.VOID);
            bill.setVoidedAt(now);
            bill.setVoidedBy(CurrentUser.id());
        }
        activity.record("GOODS_RECEIPT_VOIDED", "GOODS_RECEIPT", g.getId(),
                g.getNumber() + (bill == null ? "" : " · " + bill.getNumber()), g.getBranchId(),
                ActivityService.values("total", bill == null ? null : bill.getTotal()), null);
        return purchases.receipt(g.getId());
    }

    /** A bill always comes from a goods receipt, so voiding it voids the receipt too. */
    @Transactional
    public PurchaseService.BillResponse voidBill(Long id) {
        Bill bill = bills.lock(id);
        BusinessContext.requireBranch(bill.getBranchId());
        if (bill.getStatus() == Bill.Status.VOID) {
            throw ApiException.rule("WRONG_STATUS", bill.getStatus());
        }
        if (bill.getGoodsReceiptId() == null) {
            throw ApiException.rule("WRONG_STATUS", bill.getStatus());
        }
        voidGoodsReceipt(bill.getGoodsReceiptId());
        return purchases.bill(bill.getId());
    }

    /**
     * A supplier payment made by mistake: the bills it paid are open again by the same amounts. A refund the supplier
     * paid back (money in) goes with its supplier credit instead.
     */
    @Transactional
    public PaymentResponse voidPayment(Long id) {
        Payment p = payments.lock(id);
        BusinessContext.requireBranch(p.getBranchId());
        if (p.getPartyType() != Payment.PartyType.SUPPLIER) {
            throw ApiException.notFound("Payment");
        }
        if (p.getDirection() != Payment.Direction.OUT) {
            throw ApiException.rule("VOID_PAYMENT_IS_REFUND");
        }
        if (p.getStatus() != Payment.Status.POSTED) {
            throw ApiException.rule("WRONG_STATUS", p.getStatus());
        }
        var paid = allocations.findByFromTypeAndFromId(Allocation.FromType.PAYMENT, p.getId());
        for (Allocation a : paid) {
            if (a.getToType() == Allocation.ToType.BILL) {
                bills.lock(a.getToId()).pay(a.getAmount().negate());
            }
        }
        p.setStatus(Payment.Status.VOID);
        p.setVoidedAt(Instant.now());
        p.setVoidedBy(CurrentUser.id());
        String name = suppliers.findById(p.getPartyId()).map(s -> s.getName()).orElse("");
        activity.record("SUPPLIER_PAYMENT_VOIDED", "SUPPLIER", p.getPartyId(), p.getNumber() + " · " + name, p.getBranchId(),
                ActivityService.values("amount", p.getAmountUsd()), null);
        return PaymentResponse.from(p, paid);
    }

    /**
     * A purchase return: the goods come back into the batches they left, and its supplier credit is void. Refused when
     * the supplier already refunded the credit; credit used on bills is taken off those bills again.
     */
    @Transactional
    public PurchaseReturnResponse voidReturn(Long id) {
        PurchaseReturn r = returns.lock(id);
        BusinessContext.requireBranch(r.getBranchId());
        if (r.getStatus() != PurchaseReturn.Status.POSTED) {
            throw ApiException.rule("WRONG_STATUS", r.getStatus());
        }
        Instant now = Instant.now();
        for (SupplierCredit sc : credits.findByPurchaseReturnId(r.getId())) {
            undoCredit(sc, now);
        }
        stock.reverse(new StockService.Ref(PurchaseReturnService.REF, r.getId()));
        r.setStatus(PurchaseReturn.Status.VOID);
        r.setVoidedAt(now);
        r.setVoidedBy(CurrentUser.id());
        activity.record("PURCHASE_RETURN_VOIDED", "PURCHASE_RETURN", r.getId(), r.getNumber(), r.getBranchId(),
                ActivityService.values("total", r.getTotal()), null);
        return purchaseReturns.get(r.getId());
    }

    /** A supplier credit written by hand (a price correction). One from a return goes with its return. */
    @Transactional
    public SupplierCreditResponse voidCredit(Long id) {
        SupplierCredit sc = credits.lock(id);
        BusinessContext.requireBranch(sc.getBranchId());
        if (sc.getPurchaseReturnId() != null) {
            throw ApiException.rule("VOID_CREDIT_FROM_RETURN");
        }
        undoCredit(sc, Instant.now());
        activity.record("SUPPLIER_CREDIT_VOIDED", "SUPPLIER", sc.getSupplierId(), sc.getNumber(), sc.getBranchId(),
                ActivityService.values("amount", sc.getAmount()), null);
        return purchaseReturns.credit(sc.getId());
    }

    private void undoCredit(SupplierCredit sc, Instant now) {
        if (sc.getStatus() == SupplierCredit.Status.VOID) {
            throw ApiException.rule("WRONG_STATUS", sc.getStatus());
        }
        if (sc.getRefundedAmount().signum() != 0) {
            throw ApiException.rule("VOID_CREDIT_REFUNDED", sc.getNumber());
        }
        for (Allocation a : allocations.findByFromTypeAndFromId(Allocation.FromType.SUPPLIER_CREDIT, sc.getId())) {
            if (a.getToType() == Allocation.ToType.BILL) {
                bills.lock(a.getToId()).pay(a.getAmount().negate());
            }
            allocations.delete(a);
        }
        sc.setUsedAmount(java.math.BigDecimal.ZERO);
        sc.setStatus(SupplierCredit.Status.VOID);
        sc.setVoidedAt(now);
        sc.setVoidedBy(CurrentUser.id());
    }
}
