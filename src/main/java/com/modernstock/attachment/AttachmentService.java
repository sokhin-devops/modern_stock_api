package com.modernstock.attachment;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.FileStorage;
import com.modernstock.inventory.AdjustmentService;
import com.modernstock.inventory.StockCountService;
import com.modernstock.inventory.TransferService;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentRepository;
import com.modernstock.purchase.PurchaseReturnService;
import com.modernstock.purchase.PurchaseService;
import com.modernstock.sale.PosService;
import com.modernstock.sale.QuotationService;
import com.modernstock.sale.ReturnService;
import com.modernstock.sale.SalesOrderService;

/**
 * A photo or scan kept with any document (PLAIN.md 11.1 rule 4): the signed delivery note, the supplier's receipt, the
 * count sheet. Whoever may open the document sees it; whoever may make that kind of document adds, changes or removes
 * it. The document is read with its own service first, so its branch rules apply too.
 */
@Service
public class AttachmentService {

    /** Who may see a kind of document, who may change its attachment, and how to check this one can be opened. */
    private record Kind(List<String> view, List<String> manage, Consumer<Long> open) {
    }

    public record AttachmentInfo(boolean hasPhoto) {
    }

    private final AttachmentRepository attachments;
    private final FileStorage files;
    private final Map<String, Kind> kinds;

    public AttachmentService(AttachmentRepository attachments, FileStorage files, PurchaseService purchases,
                             PurchaseReturnService purchaseReturns, PaymentRepository payments, QuotationService quotations,
                             SalesOrderService salesOrders, PosService pos, ReturnService returns, AdjustmentService adjustments,
                             TransferService transfers, StockCountService counts) {
        this.attachments = attachments;
        this.files = files;
        List<String> orderViewers = List.of("sales_order:view", "sales_order:manage", "delivery:manage");
        this.kinds = Map.ofEntries(
                Map.entry("purchase-orders", new Kind(List.of("purchase_order:view"), List.of("purchase_order:manage"), purchases::get)),
                Map.entry("goods-receipts", new Kind(List.of("purchase_order:view", "goods_receipt:create"),
                        List.of("goods_receipt:create", "bill:manage"), purchases::receipt)),
                Map.entry("purchase-returns", new Kind(List.of("bill:manage", "purchase_return:prepare"),
                        List.of("bill:manage", "purchase_return:prepare"), purchaseReturns::get)),
                Map.entry("supplier-credits", new Kind(List.of("bill:manage"), List.of("bill:manage"), purchaseReturns::credit)),
                Map.entry("supplier-payments", new Kind(List.of("bill:manage"), List.of("bill:manage"),
                        id -> payment(payments, id, true))),
                Map.entry("quotations", new Kind(orderViewers, List.of("sales_order:manage"), quotations::get)),
                Map.entry("sales-orders", new Kind(orderViewers, List.of("sales_order:manage", "delivery:manage"), salesOrders::get)),
                Map.entry("sales-receipts", new Kind(List.of("sale:create"), List.of("sale:create"), pos::receipt)),
                Map.entry("invoices", new Kind(List.of("sale:create", "customer:view"), List.of("sale:create"), pos::invoice)),
                Map.entry("deliveries", new Kind(List.of("delivery:manage", "sale:create"), List.of("delivery:manage", "sale:create"),
                        pos::delivery)),
                Map.entry("payments-received", new Kind(List.of("sale:create"), List.of("sale:create"),
                        id -> payment(payments, id, false))),
                Map.entry("sales-returns", new Kind(List.of("sale:void", "customer:view", "sales_return:request"),
                        List.of("sale:void", "sales_return:request"), returns::get)),
                Map.entry("credit-notes", new Kind(List.of("sale:void", "customer:view"), List.of("sale:void"), returns::creditNote)),
                Map.entry("adjustments", new Kind(List.of("stock:view"), List.of("stock:opening", "adjustment:post", "adjustment:request"),
                        adjustments::get)),
                Map.entry("transfers", new Kind(List.of("stock:view"), List.of("transfer:manage"), transfers::get)),
                Map.entry("stock-counts", new Kind(List.of("count:manage"), List.of("count:manage"), counts::get)));
    }

    @Transactional(readOnly = true)
    public AttachmentInfo info(String type, Long docId) {
        open(type, docId, false);
        return new AttachmentInfo(attachments.findByDocTypeAndDocId(type, docId).isPresent());
    }

    /** The stored file's name, or null without one. */
    @Transactional(readOnly = true)
    public String photo(String type, Long docId) {
        open(type, docId, false);
        return attachments.findByDocTypeAndDocId(type, docId).map(Attachment::getPath).orElse(null);
    }

    /** Adds or replaces the photo; null removes it. JPEG, PNG or WebP up to 2 MB, like every picture. */
    @Transactional
    public AttachmentInfo set(String type, Long docId, MultipartFile file) {
        open(type, docId, true);
        Attachment a = attachments.findByDocTypeAndDocId(type, docId).orElse(null);
        String old = a == null ? null : a.getPath();
        if (file == null) {
            if (a != null) {
                attachments.delete(a);
            }
        } else {
            String saved = files.saveImage(file, FileStorage.PRIVATE_DOCUMENTS);
            if (a == null) {
                a = new Attachment();
                a.setDocType(type);
                a.setDocId(docId);
            }
            a.setPath(saved);
            attachments.save(a);
        }
        files.delete(old);
        return new AttachmentInfo(file != null);
    }

    /** 404 for an unknown kind or a document of another business, 403 without the permission or the branch. */
    private void open(String type, Long docId, boolean change) {
        Kind kind = kinds.get(type);
        if (kind == null) {
            throw ApiException.notFound("Document");
        }
        boolean allowed = kind.manage().stream().anyMatch(BusinessContext::has)
                || (!change && kind.view().stream().anyMatch(BusinessContext::has));
        if (!allowed) {
            throw ApiException.forbidden("PERMISSION_DENIED");
        }
        kind.open().accept(docId);
    }

    /** A supplier payment, or a customer's (a walk-in sale's payments have no customer but are not a supplier's). */
    private static void payment(PaymentRepository payments, Long id, boolean supplier) {
        Payment p = payments.get(id);
        if ((p.getPartyType() == Payment.PartyType.SUPPLIER) != supplier) {
            throw ApiException.notFound("Payment");
        }
        BusinessContext.requireBranch(p.getBranchId());
    }
}
