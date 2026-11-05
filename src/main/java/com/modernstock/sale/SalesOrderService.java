package com.modernstock.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BranchService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.Warehouse;
import com.modernstock.business.WarehouseRepository;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.common.Dates;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.PageResponse;
import com.modernstock.currency.CurrencyService;
import com.modernstock.inventory.StockMovement;
import com.modernstock.inventory.StockService;
import com.modernstock.money.Allocation;
import com.modernstock.money.Payment;
import com.modernstock.money.PaymentService.PaymentLine;
import com.modernstock.money.PaymentService;
import com.modernstock.partner.Customer;
import com.modernstock.partner.CustomerRepository;
import com.modernstock.sale.SaleLines.LineRequest;
import com.modernstock.sale.SalePricing.Priced;
import com.modernstock.sale.SalePricing.PricedLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Sales orders (FR-3.11, PLAIN.md 11.8): Confirmed → Partially delivered → Delivered, closed early, or cancelled. An order
 * changes nothing by itself; each delivery takes the stock and makes its invoice at the ordered prices, with its share of
 * the discounts. The last delivery takes what is left of the order's totals, so the invoices always add up to the order.
 */
@Service
@RequiredArgsConstructor
public class SalesOrderService {

    public record SalesOrderRequest(@NotNull Long customerId,
                                    @Schema(description = "Defaults to the selected branch") Long branchId,
                                    @Schema(description = "Defaults to the branch's sales warehouse") Long warehouseId,
                                    LocalDate deliveryDate,
                                    @Schema(description = "Discount on the whole order, USD") @DecimalMin("0") BigDecimal discount,
                                    @Size(max = 500) String note,
                                    @NotEmpty List<@Valid LineRequest> lines) {
    }

    public record QuickOrderRequest(@NotNull Long customerId, Long branchId, Long warehouseId,
                                    @DecimalMin("0") BigDecimal discount, @Size(max = 500) String note,
                                    @NotEmpty List<@Valid LineRequest> lines,
                                    @Schema(description = "Paid now; empty = the invoice stays unpaid") List<@Valid PaymentLine> payments) {
    }

    public record OrderLineResponse(Long id, Long productId, String productName, Long unitId, String unitSymbol,
                                    BigDecimal qtyOrdered, BigDecimal qtyDelivered, BigDecimal unitPrice, BigDecimal discount,
                                    BigDecimal lineTotal) {
    }

    public record OrderDelivery(Long deliveryId, String deliveryNumber, Instant deliveredAt, Long invoiceId, String invoiceNumber,
                                BigDecimal invoiceTotal) {
    }

    public record SalesOrderResponse(Long id, String number, Long customerId, String customerName, Long branchId,
                                     Long warehouseId, String warehouseName, Long quotationId, String quotationNumber,
                                     LocalDate orderDate, LocalDate deliveryDate, BigDecimal subtotal, BigDecimal orderDiscount,
                                     BigDecimal discount, BigDecimal vat, BigDecimal total, SalesOrder.Status status, String note,
                                     List<OrderLineResponse> lines, List<OrderDelivery> deliveries,
                                     @Schema(description = "Open order: lines that free stock (on hand less other orders' reservations) "
                                             + "does not cover yet (FR-3.21); the order can still be delivered later")
                                     List<Shortage> shortages) {
    }

    public record Shortage(Long productId, String productName,
                           @Schema(description = "Base units still to deliver") BigDecimal needed,
                           @Schema(description = "Base units free in the order's warehouse") BigDecimal free, String unitSymbol) {
    }

    public record DeliverLineRequest(@NotNull Long salesOrderItemId,
                                     @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty) {
    }

    public record DeliverRequest(@Size(max = 500) String note,
                                 @Schema(description = "Empty = everything still to deliver") List<@Valid DeliverLineRequest> lines) {
    }

    public record DeliverResponse(SalesOrderResponse order, Long deliveryId, String deliveryNumber, Long invoiceId,
                                  String invoiceNumber, BigDecimal invoiceTotal,
                                  @Schema(description = "Left to pay on the invoice, USD") BigDecimal balanceDue) {
    }

    private final SalesOrderRepository orders;
    private final ActivityService activity;
    private final QuotationRepository quotations;
    private final DeliveryRepository deliveries;
    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final WarehouseRepository warehouses;
    private final ProductService products;
    private final SalePricing pricing;
    private final SaleDocuments documents;
    private final PaymentService payments;
    private final CashShiftService cashShifts;
    private final CurrencyService currency;
    private final DocumentNumberService numbers;
    private final BranchService branches;
    private final BranchScope scope;
    private final com.modernstock.inventory.Reservations reservations;

    @Transactional
    public SalesOrderResponse create(SalesOrderRequest req) {
        SalesOrder o = newOrder();
        apply(o, req.customerId(), req.branchId(), req.warehouseId(), req.deliveryDate(), req.discount(), req.note(), req.lines());
        return toResponse(orders.save(o));
    }

    /** While nothing is delivered. Lines already on the order keep their price; new ones take today's. */
    @Transactional
    public SalesOrderResponse update(Long id, SalesOrderRequest req) {
        SalesOrder o = owned(id);
        if (o.getStatus() != SalesOrder.Status.CONFIRMED || !o.nothingDelivered()) {
            throw ApiException.rule("ORDER_NOT_EDITABLE");
        }
        apply(o, req.customerId(), req.branchId(), req.warehouseId(), req.deliveryDate(), req.discount(), req.note(), req.lines());
        return toResponse(o);
    }

    @Transactional
    public SalesOrderResponse cancel(Long id) {
        SalesOrder o = owned(id);
        if (o.getStatus() != SalesOrder.Status.CONFIRMED || !o.nothingDelivered()) {
            throw ApiException.rule("WRONG_STATUS", o.getStatus());
        }
        o.setStatus(SalesOrder.Status.CANCELLED);
        activity.record("SALES_ORDER_CANCELLED", "SALES_ORDER", o.getId(), o.getNumber(), o.getBranchId(), null,
                ActivityService.values("total", o.getTotal()));
        return toResponse(o);
    }

    /** Stop waiting to deliver the rest of a partly delivered order. */
    @Transactional
    public SalesOrderResponse close(Long id) {
        SalesOrder o = owned(id);
        if (o.getStatus() != SalesOrder.Status.PARTIALLY_DELIVERED) {
            throw ApiException.rule("WRONG_STATUS", o.getStatus());
        }
        o.setStatus(SalesOrder.Status.CLOSED);
        return toResponse(o);
    }

    @Transactional
    public DeliverResponse deliver(Long id, DeliverRequest req) {
        SalesOrder o = owned(id);
        Map<SalesOrderItem, BigDecimal> qty = new LinkedHashMap<>();
        if (req.lines() == null || req.lines().isEmpty()) {
            o.getItems().stream().filter(i -> i.toDeliver().signum() > 0).forEach(i -> qty.put(i, i.toDeliver()));
        } else {
            for (DeliverLineRequest l : req.lines()) {
                SalesOrderItem item = o.getItems().stream().filter(i -> i.getId().equals(l.salesOrderItemId())).findFirst()
                        .orElseThrow(() -> ApiException.badRequest("ORDER_LINE_UNKNOWN"));
                qty.merge(item, l.qty(), BigDecimal::add);
            }
        }
        return deliver(o, qty, req.note(), List.of());
    }

    /** "Save, deliver and invoice now": the order, its delivery and invoice, and the money paid, in one step. */
    @Transactional
    public DeliverResponse quick(QuickOrderRequest req) {
        SalesOrder o = newOrder();
        apply(o, req.customerId(), req.branchId(), req.warehouseId(), null, req.discount(), req.note(), req.lines());
        orders.save(o);
        Map<SalesOrderItem, BigDecimal> qty = new LinkedHashMap<>();
        o.getItems().forEach(i -> qty.put(i, i.getQtyOrdered()));
        return deliver(o, qty, req.note(), req.payments() == null ? List.of() : req.payments());
    }

    /** A sales order from an accepted quotation, at the quoted prices. */
    @Transactional
    public SalesOrder fromQuotation(Quotation q) {
        SalesOrder o = newOrder();
        Long branchId = BusinessContext.branchForWrite(q.getBranchId());
        Customer customer = customers.get(q.getCustomerId());
        documents.requireCreditCustomer(customer);
        o.setBranchId(branchId);
        o.setWarehouseId(branches.salesWarehouse(branchId).getId());
        o.setCustomerId(customer.getId());
        o.setQuotationId(q.getId());
        o.setNote(q.getNote());
        List<PricedLine> lines = q.getItems().stream()
                .map(i -> pricing.line(products.active(i.getProductId()), i.getUnitId(), i.getQty(), i.getUnitPrice(), i.getDiscount()))
                .toList();
        setLines(o, pricing.totals(lines, q.getOrderDiscount()));
        return orders.save(o);
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesOrderResponse> list(Long customerId, SalesOrder.Status status, Pageable pageable) {
        return PageResponse.of(orders.search(scope.branchIds(), customerId, status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public SalesOrderResponse get(Long id) {
        return toResponse(owned(id));
    }

    // ---------------------------------------------------------------- helpers

    private SalesOrder newOrder() {
        SalesOrder o = new SalesOrder();
        o.setNumber(numbers.next(DocType.SO));
        o.setOrderDate(Dates.today());
        o.setExchangeRate(currency.current().usdToKhr());
        o.setStatus(SalesOrder.Status.CONFIRMED);
        return o;
    }

    private void apply(SalesOrder o, Long customerId, Long branchId, Long warehouseId, LocalDate deliveryDate, BigDecimal discount,
                       String note, List<LineRequest> lines) {
        Long branch = BusinessContext.branchForWrite(branchId);
        Customer customer = customers.get(customerId);
        documents.requireCreditCustomer(customer); // every delivery makes an invoice the customer owes
        Warehouse w = warehouseId != null ? warehouses.get(warehouseId) : branches.salesWarehouse(branch);
        if (!w.getBranchId().equals(branch) || !w.isActive()) {
            throw ApiException.rule("WAREHOUSE_NOT_IN_BRANCH");
        }
        Map<List<Long>, BigDecimal> kept = new HashMap<>();
        o.getItems().forEach(i -> kept.put(SalePricing.key(i.getProductId(), i.getUnitId()), i.getUnitPrice()));
        o.setBranchId(branch);
        o.setWarehouseId(w.getId());
        o.setCustomerId(customer.getId());
        o.setDeliveryDate(deliveryDate);
        o.setNote(note);
        setLines(o, pricing.price(branch, SaleLines.toPricing(lines), discount, true, kept));
    }

    private static void setLines(SalesOrder o, Priced priced) {
        o.getItems().clear();
        for (PricedLine l : priced.lines()) {
            SalesOrderItem item = new SalesOrderItem();
            item.setProductId(l.product().getId());
            item.setUnitId(l.unitId());
            item.setQtyOrdered(l.qty());
            item.setUnitPrice(l.price());
            item.setDiscount(l.discount());
            o.getItems().add(item);
        }
        o.setSubtotal(priced.subtotal());
        o.setOrderDiscount(priced.orderDiscount());
        o.setDiscount(priced.discount());
        o.setVat(priced.vat());
        o.setTotal(priced.total());
    }

    private DeliverResponse deliver(SalesOrder o, Map<SalesOrderItem, BigDecimal> qty, String note, List<PaymentLine> paid) {
        Long branchId = BusinessContext.branchForWrite(o.getBranchId());
        if (o.getStatus() != SalesOrder.Status.CONFIRMED && o.getStatus() != SalesOrder.Status.PARTIALLY_DELIVERED) {
            throw ApiException.rule("WRONG_STATUS", o.getStatus());
        }
        if (qty.isEmpty()) {
            throw ApiException.rule("NOTHING_TO_DELIVER");
        }
        Customer customer = customers.get(o.getCustomerId());

        // The delivered part at the ordered prices, with its share of each line discount and of the order discount
        List<PricedLine> lines = new ArrayList<>();
        Map<PricedLine, SalesOrderItem> itemOf = new IdentityHashMap<>(); // two equal lines are still two lines
        for (Map.Entry<SalesOrderItem, BigDecimal> e : qty.entrySet()) {
            SalesOrderItem item = e.getKey();
            Product p = products.active(item.getProductId());
            if (e.getValue().compareTo(item.toDeliver()) > 0) {
                throw ApiException.rule("DELIVER_TOO_MANY", p.getNameEn(), item.toDeliver().stripTrailingZeros().toPlainString());
            }
            BigDecimal discountShare = item.getDiscount().multiply(e.getValue()).divide(item.getQtyOrdered(), 2, RoundingMode.HALF_UP);
            PricedLine line = pricing.line(p, item.getUnitId(), e.getValue(), item.getUnitPrice(), discountShare);
            lines.add(line);
            itemOf.put(line, item);
        }
        BigDecimal orderNet = o.getSubtotal().subtract(o.getDiscount().subtract(o.getOrderDiscount()));
        BigDecimal deliveredNet = lines.stream().map(PricedLine::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal orderDiscountShare = orderNet.signum() == 0 ? BigDecimal.ZERO
                : o.getOrderDiscount().multiply(deliveredNet).divide(orderNet, 2, RoundingMode.HALF_UP);
        Priced priced = pricing.totals(lines, orderDiscountShare);

        BigDecimal subtotal = priced.subtotal();
        BigDecimal discount = priced.discount();
        BigDecimal vat = priced.vat();
        boolean last = o.getItems().stream().allMatch(i -> i.toDeliver().compareTo(qty.getOrDefault(i, BigDecimal.ZERO)) <= 0);
        if (last) {
            // the order's totals less what earlier deliveries invoiced, so nothing is lost to rounding
            List<Invoice> earlier = deliveries.findBySalesOrderIdOrderById(o.getId()).stream()
                    .map(d -> invoices.findByDeliveryId(d.getId()).orElse(null))
                    .filter(i -> i != null && i.getStatus() != Invoice.Status.VOID).toList();
            subtotal = o.getSubtotal().subtract(earlier.stream().map(Invoice::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
            discount = o.getDiscount().subtract(earlier.stream().map(Invoice::getDiscount).reduce(BigDecimal.ZERO, BigDecimal::add));
            vat = o.getVat().subtract(earlier.stream().map(Invoice::getVat).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        BigDecimal total = subtotal.subtract(discount).add(vat);

        BigDecimal rate = currency.current().usdToKhr();
        BigDecimal paidUsd = paid.stream().map(pl -> PaymentService.usd(pl, rate)).reduce(BigDecimal.ZERO, BigDecimal::add);
        documents.checkCredit(customer, total.subtract(paidUsd).max(BigDecimal.ZERO));
        Delivery delivery = documents.newDelivery(branchId, o.getWarehouseId(), customer.getId(), o.getId(), note);
        Invoice invoice = documents.newInvoice(delivery, customer, rate, subtotal, discount, vat, total, note);
        StockService.Ref ref = new StockService.Ref(PosService.DELIVERY_REF, delivery.getId());
        for (PricedLine line : lines.stream().sorted(Comparator.comparing(l -> l.product().getId())).toList()) {
            SalesOrderItem item = itemOf.get(line);
            documents.sell(line, o.getWarehouseId(), StockMovement.Type.DELIVERY, ref, null, delivery.getId(), item.getId());
            item.setQtyDelivered(item.getQtyDelivered().add(line.qty()));
        }
        o.setStatus(o.getItems().stream().allMatch(i -> i.toDeliver().signum() == 0)
                ? SalesOrder.Status.DELIVERED : SalesOrder.Status.PARTIALLY_DELIVERED);

        Long shiftId = cashShifts.openShiftOf(branchId, CurrentUser.id()).map(CashShift::getId).orElse(null);
        for (PaymentLine pl : paid) {
            Payment payment = payments.record(Payment.Direction.IN, Payment.PartyType.CUSTOMER, customer.getId(), branchId, pl,
                    rate, shiftId, null);
            BigDecimal applied = payment.getAmountUsd().min(invoice.open());
            payments.allocate(payment, Allocation.ToType.INVOICE, invoice.getId(), applied);
            invoice.pay(applied);
        }
        return new DeliverResponse(toResponse(o), delivery.getId(), delivery.getNumber(), invoice.getId(), invoice.getNumber(),
                invoice.getTotal(), invoice.open());
    }

    private SalesOrder owned(Long id) {
        SalesOrder o = orders.get(id);
        BusinessContext.requireBranch(o.getBranchId());
        return o;
    }

    private SalesOrderResponse toResponse(SalesOrder o) {
        Map<Long, Product> byId = products.byIds(o.getItems().stream().map(SalesOrderItem::getProductId).toList());
        Map<Long, String> symbols = products.unitSymbols();
        List<OrderLineResponse> lines = o.getItems().stream().map(i -> new OrderLineResponse(i.getId(), i.getProductId(),
                byId.get(i.getProductId()).getNameEn(), i.getUnitId(), symbols.get(i.getUnitId()), i.getQtyOrdered(),
                i.getQtyDelivered(), i.getUnitPrice(), i.getDiscount(),
                i.getQtyOrdered().multiply(i.getUnitPrice()).setScale(2, RoundingMode.HALF_UP).subtract(i.getDiscount()))).toList();
        List<OrderDelivery> done = o.getId() == null ? List.of() : deliveries.findBySalesOrderIdOrderById(o.getId()).stream()
                .map(d -> {
                    Invoice inv = invoices.findByDeliveryId(d.getId()).orElse(null);
                    return new OrderDelivery(d.getId(), d.getNumber(), d.getDeliveredAt(), inv == null ? null : inv.getId(),
                            inv == null ? null : inv.getNumber(), inv == null ? null : inv.getTotal());
                }).toList();
        String quotationNumber = o.getQuotationId() == null ? null
                : quotations.findById(o.getQuotationId()).map(Quotation::getNumber).orElse(null);
        List<Shortage> shortages = shortages(o, byId, symbols);
        return new SalesOrderResponse(o.getId(), o.getNumber(), o.getCustomerId(),
                customers.findById(o.getCustomerId()).map(Customer::getName).orElse(null), o.getBranchId(), o.getWarehouseId(),
                branches.warehouseLabels().get(o.getWarehouseId()), o.getQuotationId(), quotationNumber, o.getOrderDate(),
                o.getDeliveryDate(), o.getSubtotal(), o.getOrderDiscount(), o.getDiscount(), o.getVat(), o.getTotal(),
                o.getStatus(), o.getNote(), lines, done, shortages);
    }

    /** Lines of an open order that the free stock of its warehouse does not cover (other orders keep theirs). */
    private List<Shortage> shortages(SalesOrder o, Map<Long, Product> byId, Map<Long, String> symbols) {
        if (o.getStatus() != SalesOrder.Status.CONFIRMED && o.getStatus() != SalesOrder.Status.PARTIALLY_DELIVERED) {
            return List.of();
        }
        Map<Long, BigDecimal> needed = new java.util.LinkedHashMap<>();
        for (SalesOrderItem i : o.getItems()) {
            BigDecimal left = i.getQtyOrdered().subtract(i.getQtyDelivered());
            if (left.signum() > 0) {
                needed.merge(i.getProductId(), products.toBaseQty(byId.get(i.getProductId()), i.getUnitId(), left), BigDecimal::add);
            }
        }
        if (needed.isEmpty()) {
            return List.of();
        }
        List<Long> warehouse = List.of(o.getWarehouseId());
        Map<Long, BigDecimal> onHand = products.onHand(needed.keySet(), warehouse);
        Map<Long, BigDecimal> others = reservations.reserved(needed.keySet(), warehouse, o.getId());
        return needed.entrySet().stream().map(e -> {
            BigDecimal free = onHand.getOrDefault(e.getKey(), BigDecimal.ZERO).subtract(others.getOrDefault(e.getKey(), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO);
            Product p = byId.get(e.getKey());
            return e.getValue().compareTo(free) > 0
                    ? new Shortage(e.getKey(), p.getNameEn(), e.getValue(), free, symbols.get(p.getBaseUnitId())) : null;
        }).filter(java.util.Objects::nonNull).toList();
    }
}
