package com.modernstock.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.PageResponse;
import com.modernstock.currency.CurrencyService;
import com.modernstock.partner.Customer;
import com.modernstock.partner.CustomerRepository;
import com.modernstock.sale.SaleLines.LineRequest;
import com.modernstock.sale.SaleLines.LineResponse;
import com.modernstock.sale.SalePricing.PricedLine;
import com.modernstock.sale.SalePricing.Priced;
import com.modernstock.sale.SalesOrderService.SalesOrderResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Quotations (FR-3.10, PLAIN.md 11.8): a price offer valid until a date. Draft → Sent → Accepted, Declined, or Expired.
 * Prices are today's for the branch, with the same discount limit as the POS; accepted, the quotation becomes a sales
 * order at the quoted prices in one step.
 */
@Service
@RequiredArgsConstructor
public class QuotationService {

    /** How long an offer stays valid when no date is given. */
    private static final int DEFAULT_VALID_DAYS = 14;

    public record QuotationRequest(@NotNull Long customerId,
                                   @Schema(description = "Defaults to the selected branch") Long branchId,
                                   @Schema(description = "Empty = 14 days from today") LocalDate validUntil,
                                   @Schema(description = "Discount on the whole quotation, USD") @DecimalMin("0") BigDecimal discount,
                                   @Size(max = 500) String note,
                                   @NotEmpty List<@Valid LineRequest> lines) {
    }

    public record QuotationResponse(Long id, String number, Long customerId, String customerName, String customerPhone,
                                    Long branchId, LocalDate quoteDate, LocalDate validUntil, BigDecimal subtotal,
                                    BigDecimal orderDiscount, BigDecimal discount, BigDecimal vat, BigDecimal total,
                                    @Schema(description = "A draft or sent offer past its date shows as EXPIRED") Quotation.Status status,
                                    Long salesOrderId, String salesOrderNumber, String note, List<LineResponse> lines) {
    }

    private final QuotationRepository quotations;
    private final SalesOrderRepository orders;
    private final CustomerRepository customers;
    private final ProductService products;
    private final SalePricing pricing;
    private final SalesOrderService salesOrders;
    private final CurrencyService currency;
    private final DocumentNumberService numbers;
    private final BranchScope scope;
    private final JdbcTemplate jdbc;

    @Transactional
    public QuotationResponse create(QuotationRequest req) {
        Quotation q = new Quotation();
        q.setNumber(numbers.next(DocType.QO));
        q.setQuoteDate(Dates.today());
        q.setStatus(Quotation.Status.DRAFT);
        apply(q, req);
        return toResponse(quotations.save(q));
    }

    /** Until it is accepted or declined. Lines already on it keep their price; a date in the future opens it again. */
    @Transactional
    public QuotationResponse update(Long id, QuotationRequest req) {
        Quotation q = owned(id);
        if (!EnumSet.of(Quotation.Status.DRAFT, Quotation.Status.SENT, Quotation.Status.EXPIRED).contains(q.getStatus())) {
            throw ApiException.rule("WRONG_STATUS", q.getStatus());
        }
        apply(q, req);
        if (q.getStatus() == Quotation.Status.EXPIRED) {
            q.setStatus(Quotation.Status.DRAFT);
        }
        return toResponse(q);
    }

    @Transactional
    public void delete(Long id) {
        Quotation q = owned(id);
        if (q.getStatus() != Quotation.Status.DRAFT) {
            throw ApiException.rule("DOCUMENT_NOT_EDITABLE");
        }
        quotations.delete(q);
    }

    /** Marks the offer as given to the customer (printed, sent by Telegram...). */
    @Transactional
    public QuotationResponse send(Long id) {
        Quotation q = open(id);
        q.setStatus(Quotation.Status.SENT);
        return toResponse(q);
    }

    @Transactional
    public QuotationResponse decline(Long id) {
        Quotation q = open(id);
        q.setStatus(Quotation.Status.DECLINED);
        return toResponse(q);
    }

    /** Accepted: becomes a sales order at the quoted prices. */
    @Transactional
    public SalesOrderResponse convert(Long id) {
        Quotation q = open(id);
        SalesOrder o = salesOrders.fromQuotation(q);
        q.setStatus(Quotation.Status.ACCEPTED);
        q.setSalesOrderId(o.getId());
        return salesOrders.get(o.getId());
    }

    @Transactional(readOnly = true)
    public PageResponse<QuotationResponse> list(Long customerId, Quotation.Status status, Pageable pageable) {
        return PageResponse.of(quotations.search(scope.branchIds(), customerId, status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public QuotationResponse get(Long id) {
        return toResponse(owned(id));
    }

    /** Every hour: drafts and sent offers past their date become expired, in every business. */
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Phnom_Penh")
    public void expireAll() {
        jdbc.update("""
                UPDATE quotations SET status = 'EXPIRED', updated_at = now()
                WHERE status IN ('DRAFT', 'SENT') AND valid_until < (now() AT TIME ZONE 'Asia/Phnom_Penh')::date
                """);
    }

    // ---------------------------------------------------------------- helpers

    private void apply(Quotation q, QuotationRequest req) {
        Long branchId = BusinessContext.branchForWrite(req.branchId());
        Customer customer = customers.get(req.customerId());
        LocalDate validUntil = req.validUntil() != null ? req.validUntil() : Dates.today().plusDays(DEFAULT_VALID_DAYS);
        if (validUntil.isBefore(Dates.today())) {
            throw ApiException.badRequest("VALID_UNTIL_PAST");
        }
        Map<List<Long>, BigDecimal> kept = new HashMap<>();
        q.getItems().forEach(i -> kept.put(SalePricing.key(i.getProductId(), i.getUnitId()), i.getUnitPrice()));
        Priced priced = pricing.price(branchId, SaleLines.toPricing(req.lines()), req.discount(), true, kept);
        q.setBranchId(branchId);
        q.setCustomerId(customer.getId());
        q.setValidUntil(validUntil);
        q.setExchangeRate(currency.current().usdToKhr());
        q.setNote(req.note());
        q.getItems().clear();
        for (PricedLine l : priced.lines()) {
            QuotationItem item = new QuotationItem();
            item.setProductId(l.product().getId());
            item.setUnitId(l.unitId());
            item.setQty(l.qty());
            item.setUnitPrice(l.price());
            item.setDiscount(l.discount());
            q.getItems().add(item);
        }
        q.setSubtotal(priced.subtotal());
        q.setOrderDiscount(priced.orderDiscount());
        q.setDiscount(priced.discount());
        q.setVat(priced.vat());
        q.setTotal(priced.total());
    }

    /** A quotation still waiting for an answer; 422 QUOTATION_EXPIRED once its date has passed. */
    private Quotation open(Long id) {
        Quotation q = owned(id);
        Quotation.Status status = q.effectiveStatus();
        if (status == Quotation.Status.EXPIRED) {
            throw ApiException.rule("QUOTATION_EXPIRED");
        }
        if (status != Quotation.Status.DRAFT && status != Quotation.Status.SENT) {
            throw ApiException.rule("WRONG_STATUS", status);
        }
        return q;
    }

    private Quotation owned(Long id) {
        Quotation q = quotations.get(id);
        BusinessContext.requireBranch(q.getBranchId());
        return q;
    }

    private QuotationResponse toResponse(Quotation q) {
        Map<Long, Product> byId = products.byIds(q.getItems().stream().map(QuotationItem::getProductId).toList());
        Map<Long, String> symbols = products.unitSymbols();
        List<LineResponse> lines = q.getItems().stream().map(i -> new LineResponse(i.getId(), i.getProductId(),
                byId.get(i.getProductId()).getNameEn(), i.getUnitId(), symbols.get(i.getUnitId()), i.getQty(), i.getUnitPrice(),
                i.getDiscount(), i.getQty().multiply(i.getUnitPrice()).setScale(2, RoundingMode.HALF_UP).subtract(i.getDiscount())))
                .toList();
        Customer c = customers.findById(q.getCustomerId()).orElse(null);
        String orderNumber = q.getSalesOrderId() == null ? null
                : orders.findById(q.getSalesOrderId()).map(SalesOrder::getNumber).orElse(null);
        return new QuotationResponse(q.getId(), q.getNumber(), q.getCustomerId(), c == null ? null : c.getName(),
                c == null ? null : c.getPhone(), q.getBranchId(), q.getQuoteDate(), q.getValidUntil(), q.getSubtotal(),
                q.getOrderDiscount(), q.getDiscount(), q.getVat(), q.getTotal(), q.effectiveStatus(), q.getSalesOrderId(),
                orderNumber, q.getNote(), lines);
    }
}
