package com.modernstock.sale;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Line shapes shared by quotations, sales orders and the price preview. */
public final class SaleLines {

    private SaleLines() {
    }

    public record LineRequest(@NotNull Long productId,
                              @Schema(description = "Empty = base unit") Long unitId,
                              @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
                              @Schema(description = "Line discount, USD") @DecimalMin("0") BigDecimal discount) {
    }

    public record LineResponse(Long id, Long productId, String productName, Long unitId, String unitSymbol, BigDecimal qty,
                               BigDecimal unitPrice, BigDecimal discount, BigDecimal lineTotal) {
    }

    public record PreviewRequest(@Schema(description = "Defaults to the selected branch") Long branchId,
                                 @NotEmpty List<@Valid LineRequest> lines,
                                 @Schema(description = "Discount on the whole document, USD") @DecimalMin("0") BigDecimal discount) {
    }

    public record Preview(List<LineResponse> lines, BigDecimal subtotal, BigDecimal discount, BigDecimal vat, BigDecimal total,
                          @Schema(description = "The discount is above the limit for members without sale:override")
                          boolean overLimit) {
    }

    static List<SalePricing.Line> toPricing(List<LineRequest> lines) {
        return lines.stream().map(l -> new SalePricing.Line(l.productId(), l.unitId(), l.qty(), l.discount())).toList();
    }

    static Preview preview(SalePricing.Priced p, Map<Long, String> symbols) {
        return new Preview(p.lines().stream().map(l -> new LineResponse(null, l.product().getId(), l.product().getNameEn(),
                l.unitId(), symbols.get(l.unitId()), l.qty(), l.price(), l.discount(), l.net())).toList(),
                p.subtotal(), p.discount(), p.vat(), p.total(), p.overLimit());
    }
}
