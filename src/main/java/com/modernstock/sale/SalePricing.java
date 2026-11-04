package com.modernstock.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.modernstock.business.BusinessContext;
import com.modernstock.business.SettingsService;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Sale prices and totals, the same at the POS, on quotations and on sales orders (PLAIN.md 11.8): the branch price (else
 * the default) of the unit; line and order discounts, together at most the Owner's limit unless sale:override; VAT on the
 * taxable lines after their share of the order discount.
 */
@Component
@RequiredArgsConstructor
public class SalePricing {

    public record Line(Long productId, Long unitId, BigDecimal qty, BigDecimal discount) {
    }

    public record PricedLine(Product product, Long unitId, BigDecimal qty, BigDecimal baseQty, BigDecimal price,
                             BigDecimal gross, BigDecimal discount) {
        public BigDecimal net() {
            return gross.subtract(discount);
        }
    }

    public record Priced(List<PricedLine> lines, BigDecimal subtotal, BigDecimal lineDiscounts, BigDecimal orderDiscount,
                         BigDecimal vat, BigDecimal total, boolean overLimit) {
        /** Line discounts and the order discount together. */
        public BigDecimal discount() {
            return lineDiscounts.add(orderDiscount);
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductService products;
    private final SettingsService settings;

    /**
     * Lines at today's prices for the branch. With enforceLimit, a discount over the limit is refused (422
     * DISCOUNT_OVER_LIMIT) unless the member may override; a preview only reports it.
     */
    public Priced price(Long branchId, List<Line> lines, BigDecimal orderDiscount, boolean enforceLimit) {
        return price(branchId, lines, orderDiscount, enforceLimit, Map.of());
    }

    /**
     * As above, but a product in a unit already on the document keeps the price it has there (keptPrices, keyed by
     * {@link #key}), so changing an order made from a quotation does not lose the quoted prices.
     */
    public Priced price(Long branchId, List<Line> lines, BigDecimal orderDiscount, boolean enforceLimit,
                        Map<List<Long>, BigDecimal> keptPrices) {
        List<PricedLine> priced = new ArrayList<>();
        for (Line l : lines) {
            Product p = products.active(l.productId());
            Long unitId = l.unitId() == null ? p.getBaseUnitId() : l.unitId();
            BigDecimal kept = keptPrices.get(key(p.getId(), unitId));
            priced.add(line(p, unitId, l.qty(), kept != null ? kept : products.priceFor(p, unitId, branchId), l.discount()));
        }
        Priced result = totals(priced, orderDiscount);
        if (enforceLimit && result.overLimit() && !BusinessContext.has("sale:override")) {
            throw ApiException.rule("DISCOUNT_OVER_LIMIT");
        }
        return result;
    }

    /** One line at a known price (a quoted or ordered price). */
    public PricedLine line(Product p, Long unitId, BigDecimal qty, BigDecimal price, BigDecimal discount) {
        BigDecimal gross = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal disc = money(discount);
        if (disc.compareTo(gross) > 0) {
            throw ApiException.badRequest("DISCOUNT_TOO_BIG");
        }
        return new PricedLine(p, unitId, qty, products.toBaseQty(p, unitId, qty), price, gross, disc);
    }

    /** Subtotal, discounts, VAT and total of lines whose prices are already set. */
    public Priced totals(List<PricedLine> lines, BigDecimal orderDiscount) {
        SettingsService.BusinessSettings s = settings.get();
        BigDecimal subtotal = sum(lines.stream().map(PricedLine::gross).toList());
        BigDecimal lineDiscounts = sum(lines.stream().map(PricedLine::discount).toList());
        BigDecimal docDiscount = money(orderDiscount);
        BigDecimal netLines = subtotal.subtract(lineDiscounts);
        if (docDiscount.compareTo(netLines) > 0) {
            throw ApiException.badRequest("DISCOUNT_TOO_BIG");
        }
        BigDecimal maxDiscount = subtotal.multiply(s.maxDiscountPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        boolean overLimit = lineDiscounts.add(docDiscount).compareTo(maxDiscount) > 0;
        BigDecimal vat = BigDecimal.ZERO;
        if (s.vatEnabled() && netLines.signum() > 0) {
            BigDecimal taxable = sum(lines.stream().filter(l -> !l.product().isVatExempt()).map(PricedLine::net).toList());
            BigDecimal taxableAfterDiscount = taxable.multiply(netLines.subtract(docDiscount)).divide(netLines, 4, RoundingMode.HALF_UP);
            vat = taxableAfterDiscount.multiply(s.vatRate()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        return new Priced(lines, subtotal, lineDiscounts, docDiscount, vat, netLines.subtract(docDiscount).add(vat), overLimit);
    }

    public static List<Long> key(Long productId, Long unitId) {
        return List.of(productId, unitId);
    }

    static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
