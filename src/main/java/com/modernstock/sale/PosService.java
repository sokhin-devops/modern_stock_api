package com.modernstock.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BranchService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.SettingsService;
import com.modernstock.business.Warehouse;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.common.Dates;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.PageResponse;
import com.modernstock.currency.CurrencyService;
import com.modernstock.inventory.StockMovement;
import com.modernstock.inventory.StockService;
import com.modernstock.money.Allocation;
import com.modernstock.money.AllocationRepository;
import com.modernstock.money.AllocationRequest;
import com.modernstock.money.BalanceService;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentRepository;
import com.modernstock.money.PaymentResponse;
import com.modernstock.money.PaymentService;
import com.modernstock.money.PaymentService.PaymentLine;
import com.modernstock.partner.Customer;
import com.modernstock.partner.CustomerRepository;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Counter sales (PLAIN.md 11.4, 11.8). Paid in full gives a sales receipt; otherwise a delivery and an invoice
 * for a named customer. Everything — numbers, lines, stock, payments — is one transaction, so a failed
 * checkout leaves nothing behind.
 */
@Service
@RequiredArgsConstructor
public class PosService {

    public static final String RECEIPT_REF = "SALES_RECEIPT";
    public static final String DELIVERY_REF = "DELIVERY";

    public record CartItem(@NotNull Long productId,
                           @Schema(description = "Empty = base unit") Long unitId,
                           @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
                           @Schema(description = "Line discount, USD") @DecimalMin("0") BigDecimal discount) {
    }

    public record CheckoutRequest(@Schema(description = "Defaults to the selected branch") Long branchId,
                                  @Schema(description = "Empty = walk-in customer (must pay in full)") Long customerId,
                                  @NotEmpty List<@Valid CartItem> items,
                                  @Schema(description = "Discount on the whole sale, USD") @DecimalMin("0") BigDecimal discount,
                                  @Schema(description = "One line per currency or method, e.g. $10 cash + ៛10,000 cash")
                                  List<@Valid PaymentLine> payments,
                                  @Size(max = 500) String note) {
    }

    public record SaleLineResponse(Long productId, String productName, Long unitId, String unitSymbol, BigDecimal qty,
                                   BigDecimal unitPrice, BigDecimal discount, BigDecimal lineTotal) {
    }

    public record CheckoutResponse(
            @Schema(description = "SALES_RECEIPT (paid in full) or INVOICE (pay later)") String documentType,
            Long id, String number, Long deliveryId, String deliveryNumber, Long customerId,
            BigDecimal subtotal, BigDecimal discount, BigDecimal vat, BigDecimal total, BigDecimal totalKhr,
            BigDecimal exchangeRate,
            @Schema(description = "Everything handed over, in riel") BigDecimal tenderedKhr,
            @Schema(description = "Change, given in riel") BigDecimal changeKhr,
            @Schema(description = "Left to pay on the invoice, USD") BigDecimal balanceDue,
            List<SaleLineResponse> lines, List<PaymentResponse> payments, List<String> warnings) {
    }

    public record VoidRequest(@NotBlank @Size(max = 255) String reason) {
    }

    public record SalesReceiptResponse(Long id, String number, Long branchId, Long warehouseId, Long cashShiftId,
                                       Long cashierId, Long customerId, Instant soldAt, BigDecimal exchangeRate,
                                       BigDecimal subtotal, BigDecimal discount, BigDecimal vat, BigDecimal total,
                                       BigDecimal totalKhr, BigDecimal changeUsd, BigDecimal changeKhr,
                                       SalesReceipt.Status status, Instant voidedAt, String note,
                                       List<SaleLineResponse> lines, List<PaymentResponse> payments) {
    }

    public record InvoiceResponse(Long id, String number, Invoice.Type type, Long branchId, Long deliveryId,
                                  Long customerId, String customerName, LocalDate invoiceDate, LocalDate dueDate,
                                  BigDecimal exchangeRate, BigDecimal subtotal, BigDecimal discount, BigDecimal vat,
                                  BigDecimal total, BigDecimal totalKhr, BigDecimal paidAmount, BigDecimal balance,
                                  Invoice.Status status, boolean overdue, List<SaleLineResponse> lines) {
    }

    public record DeliveryResponse(Long id, String number, Long branchId, Long warehouseId, Long customerId,
                                   Instant deliveredAt, Delivery.Status status, Long invoiceId,
                                   List<SaleLineResponse> lines) {
    }

    public record ReceivePaymentRequest(@NotNull Long customerId,
                                        Long branchId,
                                        @NotNull @Valid PaymentLine payment,
                                        @Schema(description = "Invoices to pay (USD). Empty = oldest due first; the rest is a deposit")
                                        List<@Valid AllocationRequest> allocations,
                                        @Size(max = 500) String note) {
    }

    private record PricedLine(CartItem item, Product product, Long unitId, BigDecimal baseQty, BigDecimal price,
                              BigDecimal gross, BigDecimal discount) {
        BigDecimal net() {
            return gross.subtract(discount);
        }
    }

    private final SalesReceiptRepository receipts;
    private final DeliveryRepository deliveries;
    private final InvoiceRepository invoices;
    private final SaleItemRepository saleItems;
    private final CustomerRepository customers;
    private final PaymentRepository paymentRepository;
    private final AllocationRepository allocations;
    private final ProductService products;
    private final StockService stock;
    private final PaymentService payments;
    private final CurrencyService currency;
    private final SettingsService settings;
    private final BalanceService balances;
    private final CashShiftService cashShifts;
    private final DocumentNumberService numbers;
    private final BranchService branches;
    private final BranchScope scope;

    @Transactional
    public CheckoutResponse checkout(CheckoutRequest req) {
        Long branchId = BusinessContext.branchForWrite(req.branchId());
        CurrencyService.Rate rate = currency.current();
        List<String> warnings = new ArrayList<>();
        if (rate.stale()) {
            warnings.add("Today's exchange rate is missing; the rate of " + rate.rateDate() + " was used.");
        }
        SettingsService.BusinessSettings s = settings.get();
        Warehouse warehouse = branches.salesWarehouse(branchId);
        Customer customer = req.customerId() == null ? null : customers.get(req.customerId());

        // 1. Prices: branch price, else the default; cashiers can't change prices, only give discounts
        List<PricedLine> lines = new ArrayList<>();
        for (CartItem item : req.items()) {
            Product p = products.active(item.productId());
            Long unitId = item.unitId() == null ? p.getBaseUnitId() : item.unitId();
            BigDecimal price = products.priceFor(p, unitId, branchId);
            BigDecimal gross = price.multiply(item.qty()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal discount = money(item.discount());
            if (discount.compareTo(gross) > 0) {
                throw ApiException.badRequest("DISCOUNT_TOO_BIG");
            }
            lines.add(new PricedLine(item, p, unitId, products.toBaseQty(p, unitId, item.qty()), price, gross, discount));
        }

        // 2. Totals, discount limit and VAT
        BigDecimal subtotal = sum(lines.stream().map(PricedLine::gross).toList());
        BigDecimal lineDiscounts = sum(lines.stream().map(PricedLine::discount).toList());
        BigDecimal docDiscount = money(req.discount());
        BigDecimal netLines = subtotal.subtract(lineDiscounts);
        if (docDiscount.compareTo(netLines) > 0) {
            throw ApiException.badRequest("DISCOUNT_TOO_BIG");
        }
        BigDecimal totalDiscount = lineDiscounts.add(docDiscount);
        BigDecimal maxDiscount = subtotal.multiply(s.maxDiscountPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (totalDiscount.compareTo(maxDiscount) > 0 && !BusinessContext.has("sale:override")) {
            throw ApiException.rule("DISCOUNT_OVER_LIMIT");
        }
        BigDecimal vat = BigDecimal.ZERO;
        if (s.vatEnabled() && netLines.signum() > 0) {
            BigDecimal taxable = sum(lines.stream().filter(l -> !l.product().isVatExempt()).map(PricedLine::net).toList());
            BigDecimal taxableAfterDiscount = taxable.multiply(netLines.subtract(docDiscount))
                    .divide(netLines, 4, RoundingMode.HALF_UP);
            vat = taxableAfterDiscount.multiply(s.vatRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        BigDecimal total = netLines.subtract(docDiscount).add(vat);
        BigDecimal totalKhr = currency.toKhr(total, rate.usdToKhr());

        // 3. Money handed over, compared in riel (PLAIN.md 11.6 example: ៛51,000 for ៛50,600 → ៛400 change)
        List<PaymentLine> paid = req.payments() == null ? List.of() : req.payments();
        BigDecimal tenderedKhr = sum(paid.stream().map(pl -> pl.currency() == Payment.Currency.USD
                ? pl.amount().multiply(rate.usdToKhr()) : pl.amount()).toList()).setScale(0, RoundingMode.HALF_UP);
        BigDecimal paidUsd = sum(paid.stream().map(pl -> PaymentService.usd(pl, rate.usdToKhr())).toList());
        boolean paidInFull = tenderedKhr.compareTo(totalKhr) >= 0;
        if (tenderedKhr.compareTo(totalKhr) > 0 && paid.stream().noneMatch(pl -> pl.method() == Payment.Method.CASH)) {
            throw ApiException.rule("OVERPAYMENT_NOT_CASH");
        }

        Long cashierId = CurrentUser.id();
        Long shiftId = cashShifts.openShiftOf(branchId, cashierId).map(CashShift::getId).orElse(null);
        Instant now = Instant.now();

        SalesReceipt receipt = null;
        Delivery delivery = null;
        Invoice invoice = null;
        StockService.Ref ref;
        if (paidInFull) {
            receipt = new SalesReceipt();
            receipt.setNumber(numbers.next(DocType.RCT));
            receipt.setBranchId(branchId);
            receipt.setWarehouseId(warehouse.getId());
            receipt.setCashShiftId(shiftId);
            receipt.setCashierId(cashierId);
            receipt.setCustomerId(customer == null ? null : customer.getId());
            receipt.setSoldAt(now);
            receipt.setExchangeRate(rate.usdToKhr());
            receipt.setSubtotal(subtotal);
            receipt.setDiscount(totalDiscount);
            receipt.setVat(vat);
            receipt.setTotal(total);
            receipt.setTotalKhr(totalKhr);
            receipt.setChangeKhr(currency.roundKhrDown(tenderedKhr.subtract(totalKhr)));
            receipt.setStatus(SalesReceipt.Status.POSTED);
            receipt.setPostedAt(now);
            receipt.setNote(req.note());
            receipts.save(receipt);
            ref = new StockService.Ref(RECEIPT_REF, receipt.getId());
        } else {
            // Pay later needs a named customer with a phone; the credit limit is checked (PLAIN.md 11.8)
            if (customer == null || customer.getPhone() == null || customer.getPhone().isBlank()) {
                throw ApiException.rule("CUSTOMER_REQUIRED_FOR_CREDIT");
            }
            BigDecimal due = total.subtract(paidUsd).max(BigDecimal.ZERO);
            if (customer.getCreditLimit().signum() > 0
                    && balances.customer(customer.getId()).add(due).compareTo(customer.getCreditLimit()) > 0
                    && !BusinessContext.has("sale:override")) {
                throw ApiException.rule("CREDIT_LIMIT_EXCEEDED", customer.getCreditLimit().toPlainString());
            }
            delivery = new Delivery();
            delivery.setNumber(numbers.next(DocType.DO));
            delivery.setBranchId(branchId);
            delivery.setWarehouseId(warehouse.getId());
            delivery.setCustomerId(customer.getId());
            delivery.setDeliveredAt(now);
            delivery.setStatus(Delivery.Status.POSTED);
            delivery.setPostedAt(now);
            delivery.setNote(req.note());
            deliveries.save(delivery);

            invoice = new Invoice();
            invoice.setNumber(numbers.next(DocType.INV));
            invoice.setBranchId(branchId);
            invoice.setDeliveryId(delivery.getId());
            invoice.setCustomerId(customer.getId());
            invoice.setInvoiceDate(Dates.today());
            invoice.setDueDate(Dates.today().plusDays(customer.getPaymentTermsDays()));
            invoice.setExchangeRate(rate.usdToKhr());
            invoice.setSubtotal(subtotal);
            invoice.setDiscount(totalDiscount);
            invoice.setVat(vat);
            invoice.setTotal(total);
            invoice.setTotalKhr(totalKhr);
            invoice.setStatus(Invoice.Status.UNPAID);
            invoice.setNote(req.note());
            invoices.save(invoice);
            ref = new StockService.Ref(DELIVERY_REF, delivery.getId());
        }

        // 4. Stock: rows locked in product ID order so two cashiers never deadlock; FEFO, expired batches skipped
        StockMovement.Type type = paidInFull ? StockMovement.Type.SALES_RECEIPT : StockMovement.Type.DELIVERY;
        for (PricedLine line : lines.stream().sorted(Comparator.comparing(l -> l.product().getId())).toList()) {
            stock.issue(line.product(), warehouse.getId(), line.baseQty(), type, ref, false);
            SaleItem item = new SaleItem();
            item.setSalesReceiptId(receipt == null ? null : receipt.getId());
            item.setDeliveryId(delivery == null ? null : delivery.getId());
            item.setProductId(line.product().getId());
            item.setUnitId(line.unitId());
            item.setQty(line.item().qty());
            item.setBaseQty(line.baseQty());
            item.setUnitPrice(line.price());
            item.setDiscount(line.discount());
            item.setCostAtSale(line.product().getAvgCost());
            saleItems.save(item);
        }

        // 5. Payments: one per currency or method, linked to the receipt or invoice
        List<PaymentResponse> paymentViews = new ArrayList<>();
        for (PaymentLine pl : paid) {
            Payment payment = payments.record(Payment.Direction.IN,
                    customer == null ? Payment.PartyType.WALK_IN : Payment.PartyType.CUSTOMER,
                    customer == null ? null : customer.getId(), branchId, pl, rate.usdToKhr(), shiftId, null);
            if (receipt != null) {
                payments.allocate(payment, Allocation.ToType.SALES_RECEIPT, receipt.getId(), payment.getAmountUsd());
            } else {
                BigDecimal applied = payment.getAmountUsd().min(invoice.open());
                payments.allocate(payment, Allocation.ToType.INVOICE, invoice.getId(), applied);
                invoice.pay(applied);
            }
            paymentViews.add(view(payment));
        }

        List<SaleLineResponse> lineViews = lines.stream().map(l -> new SaleLineResponse(l.product().getId(),
                l.product().getNameEn(), l.unitId(), products.unitSymbols().get(l.unitId()), l.item().qty(), l.price(),
                l.discount(), l.net())).toList();
        return receipt != null
                ? new CheckoutResponse(RECEIPT_REF, receipt.getId(), receipt.getNumber(), null, null,
                        receipt.getCustomerId(), subtotal, totalDiscount, vat, total, totalKhr, rate.usdToKhr(),
                        tenderedKhr, receipt.getChangeKhr(), BigDecimal.ZERO, lineViews, paymentViews, warnings)
                : new CheckoutResponse("INVOICE", invoice.getId(), invoice.getNumber(), delivery.getId(),
                        delivery.getNumber(), customer.getId(), subtotal, totalDiscount, vat, total, totalKhr,
                        rate.usdToKhr(), tenderedKhr, BigDecimal.ZERO, invoice.open(), lineViews, paymentViews,
                        warnings);
    }

    /** Only the Owner or Manager can void. Stock goes back into the same batches; the payments are voided. */
    @Transactional
    public SalesReceiptResponse voidReceipt(Long id, VoidRequest req) {
        SalesReceipt r = receipts.get(id);
        BusinessContext.requireBranch(r.getBranchId());
        if (r.getStatus() != SalesReceipt.Status.POSTED) {
            throw ApiException.rule("WRONG_STATUS", r.getStatus());
        }
        stock.reverse(new StockService.Ref(RECEIPT_REF, r.getId()));
        for (Allocation a : allocations.findByToTypeAndToId(Allocation.ToType.SALES_RECEIPT, r.getId())) {
            paymentRepository.get(a.getFromId()).setStatus(Payment.Status.VOID);
        }
        r.setStatus(SalesReceipt.Status.VOID);
        r.setVoidedAt(Instant.now());
        r.setVoidedBy(CurrentUser.id());
        r.setNote(r.getNote() == null ? "Void: " + req.reason() : r.getNote() + " | Void: " + req.reason());
        return toResponse(r);
    }

    /** Pays invoices, oldest due first unless listed; money left over is a deposit. */
    @Transactional
    public PaymentResponse receivePayment(ReceivePaymentRequest req) {
        Customer customer = customers.get(req.customerId());
        Long branchId = BusinessContext.branchForWrite(req.branchId());
        BigDecimal rate = currency.current().usdToKhr();
        Long shiftId = cashShifts.openShiftOf(branchId, CurrentUser.id()).map(CashShift::getId).orElse(null);
        Payment p = payments.record(Payment.Direction.IN, Payment.PartyType.CUSTOMER, customer.getId(), branchId,
                req.payment(), rate, shiftId, req.note());
        BigDecimal left = p.getAmountUsd();
        if (req.allocations() != null && !req.allocations().isEmpty()) {
            for (AllocationRequest a : req.allocations()) {
                Invoice inv = invoices.get(a.documentId());
                if (!inv.getCustomerId().equals(customer.getId()) || inv.getStatus() == Invoice.Status.VOID) {
                    throw ApiException.notFound("Invoice");
                }
                if (a.amount().compareTo(inv.open()) > 0 || a.amount().compareTo(left) > 0) {
                    throw ApiException.rule("ALLOCATION_TOO_BIG");
                }
                inv.pay(a.amount());
                payments.allocate(p, Allocation.ToType.INVOICE, inv.getId(), a.amount());
                left = left.subtract(a.amount());
            }
        } else {
            for (Invoice inv : invoices.findByCustomerIdAndStatusInOrderByDueDateAscIdAsc(customer.getId(),
                    EnumSet.of(Invoice.Status.UNPAID, Invoice.Status.PARTLY_PAID))) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal amount = left.min(inv.open());
                inv.pay(amount);
                payments.allocate(p, Allocation.ToType.INVOICE, inv.getId(), amount);
                left = left.subtract(amount);
            }
        }
        return view(p);
    }

    // ------------------------------------------------------------------ reading

    /** Cashiers see only their own sales (PLAIN.md 14). */
    @Transactional(readOnly = true)
    public PageResponse<SalesReceiptResponse> receipts(SalesReceipt.Status status, Pageable pageable) {
        Long cashier = BusinessContext.has("cash_shift:all") ? null : CurrentUser.id();
        return PageResponse.of(receipts.search(scope.branchIds(), cashier, status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public SalesReceiptResponse receipt(Long id) {
        SalesReceipt r = receipts.get(id);
        BusinessContext.requireBranch(r.getBranchId());
        if (!BusinessContext.has("cash_shift:all") && !r.getCashierId().equals(CurrentUser.id())) {
            throw ApiException.notFound("Sales receipt");
        }
        return toResponse(r);
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> invoices(Long customerId, Invoice.Status status, Pageable pageable) {
        return PageResponse.of(invoices.search(scope.branchIds(), customerId, status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse invoice(Long id) {
        Invoice inv = invoices.get(id);
        BusinessContext.requireBranch(inv.getBranchId());
        return toResponse(inv);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> deliveries(Long customerId, Pageable pageable) {
        return PageResponse.of(deliveries.search(scope.branchIds(), customerId, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public DeliveryResponse delivery(Long id) {
        Delivery d = deliveries.get(id);
        BusinessContext.requireBranch(d.getBranchId());
        return toResponse(d);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> paymentsReceived(Long customerId, Pageable pageable) {
        return PageResponse.of(paymentRepository.search(Payment.Direction.IN, scope.branchIds(), customerId, pageable),
                this::view);
    }

    private PaymentResponse view(Payment p) {
        return PaymentResponse.from(p, allocations.findByFromTypeAndFromId(Allocation.FromType.PAYMENT, p.getId()));
    }

    private List<SaleLineResponse> lineViews(List<SaleItem> items) {
        Map<Long, Product> byId = products.byIds(items.stream().map(SaleItem::getProductId).toList());
        Map<Long, String> symbols = products.unitSymbols();
        return items.stream().map(i -> new SaleLineResponse(i.getProductId(), byId.get(i.getProductId()).getNameEn(),
                i.getUnitId(), symbols.get(i.getUnitId()), i.getQty(), i.getUnitPrice(), i.getDiscount(),
                i.getUnitPrice().multiply(i.getQty()).setScale(2, RoundingMode.HALF_UP).subtract(i.getDiscount())))
                .toList();
    }

    private SalesReceiptResponse toResponse(SalesReceipt r) {
        List<PaymentResponse> paymentViews = allocations.findByToTypeAndToId(Allocation.ToType.SALES_RECEIPT, r.getId())
                .stream().map(a -> view(paymentRepository.get(a.getFromId()))).toList();
        return new SalesReceiptResponse(r.getId(), r.getNumber(), r.getBranchId(), r.getWarehouseId(),
                r.getCashShiftId(), r.getCashierId(), r.getCustomerId(), r.getSoldAt(), r.getExchangeRate(),
                r.getSubtotal(), r.getDiscount(), r.getVat(), r.getTotal(), r.getTotalKhr(), r.getChangeUsd(),
                r.getChangeKhr(), r.getStatus(), r.getVoidedAt(), r.getNote(),
                lineViews(saleItems.findBySalesReceiptIdOrderById(r.getId())), paymentViews);
    }

    private InvoiceResponse toResponse(Invoice i) {
        boolean overdue = (i.getStatus() == Invoice.Status.UNPAID || i.getStatus() == Invoice.Status.PARTLY_PAID)
                && i.getDueDate().isBefore(Dates.today());
        List<SaleLineResponse> lines = i.getDeliveryId() == null ? List.of()
                : lineViews(saleItems.findByDeliveryIdOrderById(i.getDeliveryId()));
        return new InvoiceResponse(i.getId(), i.getNumber(), i.getType(), i.getBranchId(), i.getDeliveryId(),
                i.getCustomerId(), customers.get(i.getCustomerId()).getName(), i.getInvoiceDate(), i.getDueDate(),
                i.getExchangeRate(), i.getSubtotal(), i.getDiscount(), i.getVat(), i.getTotal(), i.getTotalKhr(),
                i.getPaidAmount(), i.open(), i.getStatus(), overdue, lines);
    }

    private DeliveryResponse toResponse(Delivery d) {
        return new DeliveryResponse(d.getId(), d.getNumber(), d.getBranchId(), d.getWarehouseId(), d.getCustomerId(),
                d.getDeliveredAt(), d.getStatus(), invoices.findByDeliveryId(d.getId()).map(Invoice::getId).orElse(null),
                lineViews(saleItems.findByDeliveryIdOrderById(d.getId())));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
