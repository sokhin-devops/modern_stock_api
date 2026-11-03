package com.modernstock.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.billing.EntitlementService;
import com.modernstock.business.BranchRepository;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BranchService;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.PageResponse;
import com.modernstock.inventory.StockLevel;
import com.modernstock.inventory.StockLevelRepository;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Products, packaging units, branch prices, and the unit and price rules every document uses. */
@Service
@RequiredArgsConstructor
public class ProductService {

    public record ProductUnitRequest(
            @NotNull Long unitId,
            @Schema(description = "How many base units are in one of this unit, e.g. 24", example = "24")
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal factor,
            @Size(max = 40) String barcode,
            @Schema(description = "Price for one of this unit; empty = base price × factor") @DecimalMin("0") BigDecimal sellPrice) {
    }

    public record ProductRequest(
            @Schema(description = "Generated (P00001...) when empty") @Size(max = 40) String sku,
            @Size(max = 40) String barcode,
            @NotBlank @Size(max = 160) String nameEn,
            @Size(max = 160) String nameKm,
            Long categoryId,
            @Schema(description = "Stock is kept in this unit, usually Piece") @NotNull Long baseUnitId,
            @Schema(description = "Default price of one base unit, USD") @NotNull @DecimalMin("0") BigDecimal sellPrice,
            @DecimalMin("0") BigDecimal reorderLevel,
            @Min(0) Integer leadTimeDays,
            boolean trackExpiry,
            boolean vatExempt,
            @Valid List<ProductUnitRequest> units) {
    }

    public record ProductUnitResponse(Long unitId, String unitSymbol, BigDecimal factor, String barcode,
                                      BigDecimal sellPrice) {
    }

    public record ProductResponse(Long id, String sku, String barcode, String nameEn, String nameKm, Long categoryId,
                                  Long baseUnitId, String baseUnitSymbol, BigDecimal sellPrice,
                                  @Schema(description = "Only for members who manage products") BigDecimal avgCost,
                                  BigDecimal reorderLevel, int leadTimeDays, boolean trackExpiry, boolean vatExempt,
                                  boolean active, List<ProductUnitResponse> units,
                                  @Schema(description = "Base units on hand in the branches you are looking at") BigDecimal qtyOnHand) {
    }

    public record BarcodeLookupResponse(ProductResponse product, Long unitId, BigDecimal factor,
                                        @Schema(description = "Price of one scanned unit in this branch") BigDecimal price,
                                        @Schema(description = "Base units in the branch's sales warehouse") BigDecimal qtyOnHand) {
    }

    public record BranchPriceRequest(@NotNull Long branchId, @NotNull Long unitId,
                                     @Schema(description = "Empty removes the override") @DecimalMin("0") BigDecimal sellPrice) {
    }

    public record BranchPriceResponse(Long branchId, Long unitId, BigDecimal sellPrice) {
    }

    private final ProductRepository products;
    private final UnitRepository units;
    private final CategoryRepository categories;
    private final BranchPriceRepository branchPrices;
    private final BranchRepository branches;
    private final StockLevelRepository levels;
    private final BranchScope scope;
    private final BranchService branchService;
    private final EntitlementService entitlements;

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(String q, Long categoryId, Boolean active, Pageable pageable) {
        Page<Product> page = products.search(like(q), categoryId, active == null ? Boolean.TRUE : active, pageable);
        Map<Long, BigDecimal> onHand = onHand(page.getContent().stream().map(Product::getId).toList(), scope.warehouseIds());
        Map<Long, String> symbols = unitSymbols();
        return PageResponse.of(page, p -> toResponse(p, symbols, onHand.getOrDefault(p.getId(), BigDecimal.ZERO)));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        Product p = products.get(id);
        return toResponse(p, unitSymbols(), onHand(List.of(id), scope.warehouseIds()).getOrDefault(id, BigDecimal.ZERO));
    }

    @Transactional
    public ProductResponse create(ProductRequest req) {
        entitlements.checkCanAdd(EntitlementService.PRODUCTS);
        Product p = new Product();
        p.setSku(req.sku() == null || req.sku().isBlank() ? nextSku() : req.sku().trim());
        if (products.existsBySkuIgnoreCase(p.getSku())) {
            throw ApiException.conflict("SKU_TAKEN", p.getSku());
        }
        apply(p, req);
        products.save(p);
        return toResponse(p, unitSymbols(), BigDecimal.ZERO);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product p = products.get(id);
        if (req.sku() != null && !req.sku().isBlank() && !req.sku().trim().equalsIgnoreCase(p.getSku())) {
            if (products.existsBySkuIgnoreCase(req.sku().trim())) {
                throw ApiException.conflict("SKU_TAKEN", req.sku());
            }
            p.setSku(req.sku().trim());
        }
        apply(p, req);
        return get(id);
    }

    /** Products are never deleted: old documents point to them. */
    @Transactional
    public void deactivate(Long id) {
        products.get(id).setActive(false);
    }

    /** Scanner lookup for the POS: matches the product barcode or a packaging unit's barcode. */
    @Transactional(readOnly = true)
    public BarcodeLookupResponse byBarcode(String code, Long branchId) {
        Long branch = BusinessContext.branchForWrite(branchId);
        Product p = products.findByBarcode(code)
                .or(() -> products.findByUnitBarcode(code))
                .filter(Product::isActive)
                .orElseThrow(() -> ApiException.notFound("Barcode " + code));
        Long unitId = p.getUnits().stream().filter(u -> code.equals(u.getBarcode())).map(ProductUnit::getUnitId)
                .findFirst().orElse(p.getBaseUnitId());
        Long salesWarehouse = branchService.salesWarehouse(branch).getId();
        BigDecimal qty = onHand(List.of(p.getId()), List.of(salesWarehouse)).getOrDefault(p.getId(), BigDecimal.ZERO);
        return new BarcodeLookupResponse(toResponse(p, unitSymbols(), qty), unitId, factor(p, unitId),
                priceFor(p, unitId, branch), qty);
    }

    @Transactional(readOnly = true)
    public List<BranchPriceResponse> branchPrices(Long productId) {
        products.get(productId);
        return branchPrices.findByProductId(productId).stream()
                .filter(bp -> BusinessContext.canAccessBranch(bp.getBranchId()))
                .map(bp -> new BranchPriceResponse(bp.getBranchId(), bp.getUnitId(), bp.getSellPrice())).toList();
    }

    @Transactional
    public List<BranchPriceResponse> setBranchPrices(Long productId, List<BranchPriceRequest> req) {
        Product p = products.get(productId);
        for (BranchPriceRequest r : req) {
            branches.get(r.branchId());
            BusinessContext.requireBranch(r.branchId());
            factor(p, r.unitId());
            BranchPrice bp = branchPrices.findByBranchIdAndProductIdAndUnitId(r.branchId(), productId, r.unitId())
                    .orElse(null);
            if (r.sellPrice() == null) {
                if (bp != null) {
                    branchPrices.delete(bp);
                }
                continue;
            }
            if (bp == null) {
                bp = new BranchPrice();
                bp.setBranchId(r.branchId());
                bp.setProductId(productId);
                bp.setUnitId(r.unitId());
            }
            bp.setSellPrice(r.sellPrice());
            branchPrices.save(bp);
        }
        branchPrices.flush();
        return branchPrices(productId);
    }

    // ------------------------------------------------------------------ rules used by documents

    /** An active product of the open business, for a document line. */
    @Transactional(readOnly = true)
    public Product active(Long id) {
        Product p = products.get(id);
        if (!p.isActive()) {
            throw ApiException.rule("PRODUCT_INACTIVE", p.getNameEn());
        }
        return p;
    }

    /** 1 for the base unit, the packaging factor otherwise (2 boxes of 24 = 48 pcs). */
    public BigDecimal factor(Product p, Long unitId) {
        if (unitId == null || unitId.equals(p.getBaseUnitId())) {
            return BigDecimal.ONE;
        }
        return p.unit(unitId).map(ProductUnit::getFactor)
                .orElseThrow(() -> ApiException.rule("UNIT_NOT_FOR_PRODUCT", p.getNameEn()));
    }

    public BigDecimal toBaseQty(Product p, Long unitId, BigDecimal qty) {
        return qty.multiply(factor(p, unitId));
    }

    /** Branch price, else the unit's own price, else the base price × factor. */
    @Transactional(readOnly = true)
    public BigDecimal priceFor(Product p, Long unitId, Long branchId) {
        Long unit = unitId == null ? p.getBaseUnitId() : unitId;
        return branchPrices.findByBranchIdAndProductIdAndUnitId(branchId, p.getId(), unit)
                .map(BranchPrice::getSellPrice)
                .or(() -> p.unit(unit).map(ProductUnit::getSellPrice).filter(Objects::nonNull))
                .orElseGet(() -> p.getSellPrice().multiply(factor(p, unit)).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    public Map<Long, BigDecimal> onHand(Collection<Long> productIds, Collection<Long> warehouseIds) {
        if (productIds.isEmpty() || warehouseIds.isEmpty()) {
            return Map.of();
        }
        return levels.findByProductIdInAndWarehouseIdIn(productIds, warehouseIds).stream()
                .collect(Collectors.groupingBy(StockLevel::getProductId,
                        Collectors.reducing(BigDecimal.ZERO, StockLevel::getQtyOnHand, BigDecimal::add)));
    }

    public Map<Long, String> unitSymbols() {
        return units.findAll().stream().collect(Collectors.toMap(Unit::getId, Unit::getSymbol));
    }

    public Map<Long, Product> byIds(Collection<Long> ids) {
        return products.findAllById(ids).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private void apply(Product p, ProductRequest req) {
        String barcode = req.barcode() == null || req.barcode().isBlank() ? null : req.barcode().trim();
        if (barcode != null && !barcode.equals(p.getBarcode())) {
            products.findByBarcode(barcode).ifPresent(other -> {
                throw ApiException.conflict("BARCODE_TAKEN", barcode);
            });
        }
        units.get(req.baseUnitId());
        if (req.categoryId() != null) {
            categories.get(req.categoryId());
        }
        p.setBarcode(barcode);
        p.setNameEn(req.nameEn().trim());
        p.setNameKm(req.nameKm());
        p.setCategoryId(req.categoryId());
        p.setBaseUnitId(req.baseUnitId());
        p.setSellPrice(req.sellPrice());
        p.setReorderLevel(req.reorderLevel() == null ? BigDecimal.ZERO : req.reorderLevel());
        p.setLeadTimeDays(req.leadTimeDays() == null ? 0 : req.leadTimeDays());
        p.setTrackExpiry(req.trackExpiry());
        p.setVatExempt(req.vatExempt());

        // update packaging in place: deleting and re-adding the same unit would break the unique key
        List<ProductUnitRequest> wanted = req.units() == null ? List.of() : req.units();
        p.getUnits().removeIf(u -> wanted.stream().noneMatch(w -> w.unitId().equals(u.getUnitId())));
        List<ProductUnit> added = new ArrayList<>();
        for (ProductUnitRequest w : wanted) {
            if (w.unitId().equals(req.baseUnitId())) {
                continue;
            }
            units.get(w.unitId());
            ProductUnit pu = p.unit(w.unitId()).orElseGet(() -> {
                ProductUnit created = new ProductUnit();
                created.setUnitId(w.unitId());
                added.add(created);
                return created;
            });
            pu.setFactor(w.factor());
            pu.setBarcode(w.barcode() == null || w.barcode().isBlank() ? null : w.barcode().trim());
            pu.setSellPrice(w.sellPrice());
        }
        p.getUnits().addAll(added);
    }

    private String nextSku() {
        long n = products.count() + 1;
        String sku;
        do {
            sku = "P%05d".formatted(n++);
        } while (products.existsBySkuIgnoreCase(sku));
        return sku;
    }

    private ProductResponse toResponse(Product p, Map<Long, String> symbols, BigDecimal qtyOnHand) {
        List<ProductUnitResponse> unitList = p.getUnits().stream()
                .map(u -> new ProductUnitResponse(u.getUnitId(), symbols.get(u.getUnitId()), u.getFactor(),
                        u.getBarcode(), u.getSellPrice()))
                .toList();
        return new ProductResponse(p.getId(), p.getSku(), p.getBarcode(), p.getNameEn(), p.getNameKm(),
                p.getCategoryId(), p.getBaseUnitId(), symbols.get(p.getBaseUnitId()), p.getSellPrice(),
                BusinessContext.has("product:manage") ? p.getAvgCost() : null, p.getReorderLevel(),
                p.getLeadTimeDays(), p.isTrackExpiry(), p.isVatExempt(), p.isActive(), unitList, qtyOnHand);
    }

    static String like(String q) {
        return q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase() + "%";
    }
}
