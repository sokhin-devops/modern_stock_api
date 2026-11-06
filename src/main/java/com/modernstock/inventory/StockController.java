package com.modernstock.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.Branch;
import com.modernstock.business.BranchRepository;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BranchService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.ExpiryWindows;
import com.modernstock.business.Warehouse;
import com.modernstock.business.WarehouseRepository;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductRepository;
import com.modernstock.catalog.ProductService;
import com.modernstock.catalog.ReorderLevels;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Read-only stock views. Everything is filtered by the branches the request may see. */
@Tag(name = "Stock")
@RestController
@RequestMapping("/stock")
@RequiredArgsConstructor
public class StockController {

    public record StockRow(Long productId, String sku, String nameEn, String nameKm, String unitSymbol,
                           BigDecimal qtyOnHand, BigDecimal reorderLevel,
                           @Schema(description = "qty × average cost; only for members who manage products") BigDecimal value,
                           boolean low,
                           @Schema(description = "Of qtyOnHand, reserved for sales orders (FR-3.21); free = qtyOnHand − reserved")
                           BigDecimal reserved) {
    }

    public record BranchQty(Long branchId, BigDecimal qtyOnHand, BigDecimal reorderLevel, boolean low) {
    }

    public record BranchStockRow(Long productId, String sku, String nameEn, String nameKm, String unitSymbol,
                                 @Schema(description = "All the branches below added up") BigDecimal qtyOnHand,
                                 @Schema(description = "One per branch you may use, main branch first") List<BranchQty> branches) {
    }

    public record BatchRow(Long id, Long productId, String productName, Long warehouseId, String warehouseName,
                           String batchNo, LocalDate expiryDate, BigDecimal qtyOnHand, boolean expired) {
    }

    public record WarehouseStock(Long warehouseId, String warehouseName, BigDecimal qtyOnHand, List<BatchRow> batches) {
    }

    public record ProductStock(Long productId, String nameEn, BigDecimal qtyOnHand, List<WarehouseStock> warehouses) {
    }

    public record MovementRow(Long id, Instant at, Long productId, String productName, Long warehouseId,
                              String warehouseName, Long batchId, StockMovement.Type type, BigDecimal qty,
                              BigDecimal balanceAfter, String referenceType, Long referenceId) {
    }

    public record StockSummary(BigDecimal onHandValue,
                               @Schema(description = "Σ qty sent × unit cost of transfers in transit, shown as its own line")
                               BigDecimal inTransitValue,
                               long lowStockItems,
                               @Schema(description = "Batches expiring within the first near-expiry window (FR-7.10)") long expiringSoon,
                               @Schema(description = "That window in days, 30 unless changed in Settings › Rules") int expiringDays,
                               long expiredBatches) {
    }

    private final ProductRepository products;
    private final ProductService productService;
    private final StockLevelRepository levels;
    private final StockBatchRepository batches;
    private final StockMovementRepository movements;
    private final StockTransferRepository transfers;
    private final BranchScope scope;
    private final BranchService branches;
    private final ReorderLevels reorderLevels;
    private final com.modernstock.catalog.ReorderPoints reorderPoints;
    private final BranchRepository branchRepository;
    private final WarehouseRepository warehouses;
    private final ExpiryWindows expiryWindows;
    private final Reservations reservations;

    @Operation(summary = "Stock on hand per product", description = "Base units, summed over the branches you see, or in one "
            + "warehouse (warehouseId, PLAIN.md 12.2 stock per warehouse). q matches name, SKU or barcode.")
    @GetMapping("/levels")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public PageResponse<StockRow> stockLevels(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) Long categoryId,
                                         @RequestParam(required = false) Long warehouseId,
                                         @ParameterObject @PageableDefault(size = 20, sort = "nameEn") Pageable pageable) {
        Page<Product> page = products.search(like(q), categoryId, true, pageable);
        List<Long> visible = oneOrAll(warehouseId);
        Map<Long, BigDecimal> onHand = productService.onHand(page.getContent().stream().map(Product::getId).toList(), visible);
        Map<Long, String> symbols = productService.unitSymbols();
        Map<Long, BigDecimal> reorderAt = reorderLevels.inScope(page.getContent());
        Map<Long, BigDecimal> reserved = reservations.reserved(onHand.keySet(), visible, null);
        return PageResponse.of(page, p -> row(p, onHand.getOrDefault(p.getId(), BigDecimal.ZERO), reorderAt.get(p.getId()), symbols,
                reserved.getOrDefault(p.getId(), BigDecimal.ZERO)));
    }

    @Operation(summary = "Stock of all branches side by side", description = "On hand and the reorder level in each branch you "
            + "may use, whatever branch is picked in the top bar (FR-4.8). lowOnly keeps products low in at least one branch.")
    @GetMapping("/by-branch")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public PageResponse<BranchStockRow> stockByBranch(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) Long categoryId,
                                                      @RequestParam(defaultValue = "false") boolean lowOnly,
                                                      @ParameterObject @PageableDefault(size = 20, sort = "nameEn") Pageable pageable) {
        List<Long> branchIds = branchRepository.findAll().stream()
                .filter(b -> b.isActive() && BusinessContext.canAccessBranch(b.getId()))
                .sorted(Comparator.comparing((Branch b) -> !b.isMain()).thenComparing(Branch::getId))
                .map(Branch::getId).toList();
        Map<Long, Long> branchOf = warehouses.findAll().stream()
                .filter(w -> branchIds.contains(w.getBranchId()))
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getBranchId));
        Map<Long, String> symbols = productService.unitSymbols();
        Function<List<Product>, List<BranchStockRow>> rows = list -> {
            Map<Long, Map<Long, BigDecimal>> levelsOf = reorderLevels.perBranch(list, branchIds);
            Map<Long, Map<Long, BigDecimal>> qtyOf = new HashMap<>();
            if (!list.isEmpty()) {
                for (StockLevel l : levels.findByProductIdIn(list.stream().map(Product::getId).toList())) {
                    Long branchId = branchOf.get(l.getWarehouseId());
                    if (branchId != null) {
                        qtyOf.computeIfAbsent(l.getProductId(), k -> new HashMap<>()).merge(branchId, l.getQtyOnHand(), BigDecimal::add);
                    }
                }
            }
            return list.stream().map(p -> {
                List<BranchQty> cells = branchIds.stream().map(id -> {
                    BigDecimal qty = qtyOf.getOrDefault(p.getId(), Map.of()).getOrDefault(id, BigDecimal.ZERO);
                    BigDecimal level = levelsOf.get(p.getId()).get(id);
                    return new BranchQty(id, qty, level, level.signum() > 0 && qty.compareTo(level) <= 0);
                }).toList();
                return new BranchStockRow(p.getId(), p.getSku(), p.getNameEn(), p.getNameKm(), symbols.get(p.getBaseUnitId()),
                        cells.stream().map(BranchQty::qtyOnHand).reduce(BigDecimal.ZERO, BigDecimal::add), cells);
            }).toList();
        };
        if (!lowOnly) {
            Page<Product> page = products.search(like(q), categoryId, true, pageable);
            return PageResponse.of(new PageImpl<>(rows.apply(page.getContent()), pageable, page.getTotalElements()));
        }
        // Low somewhere: every matching product is checked, then the page is cut here
        List<BranchStockRow> low = rows.apply(products.search(like(q), categoryId, true, Pageable.unpaged(pageable.getSort())).getContent())
                .stream().filter(r -> r.branches().stream().anyMatch(BranchQty::low)).toList();
        int from = (int) Math.min(pageable.getOffset(), low.size());
        return PageResponse.of(new PageImpl<>(low.subList(from, Math.min(from + pageable.getPageSize(), low.size())), pageable, low.size()));
    }

    @Operation(summary = "Low stock", description = "Products at or below their reorder level (each branch's own level, summed over the branches you see).")
    @GetMapping("/low")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public List<StockRow> lowStock() {
        List<Product> active = products.findAll().stream().filter(Product::isActive).toList();
        Map<Long, BigDecimal> reorderAt = reorderLevels.inScope(active);
        List<Product> watched = active.stream().filter(p -> reorderAt.get(p.getId()).signum() > 0).toList();
        Map<Long, BigDecimal> onHand = productService.onHand(watched.stream().map(Product::getId).toList(),
                scope.warehouseIds());
        Map<Long, String> symbols = productService.unitSymbols();
        return watched.stream()
                .map(p -> row(p, onHand.getOrDefault(p.getId(), BigDecimal.ZERO), reorderAt.get(p.getId()), symbols, BigDecimal.ZERO))
                .filter(StockRow::low)
                .sorted(Comparator.comparing(StockRow::qtyOnHand))
                .toList();
    }

    @Operation(summary = "One product: stock per warehouse and batch")
    @GetMapping("/levels/{productId}")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public ProductStock productStock(@PathVariable Long productId) {
        Product p = products.get(productId);
        List<Long> visible = scope.warehouseIds();
        Map<Long, String> labels = branches.warehouseLabels();
        LocalDate today = Dates.today();
        List<WarehouseStock> rows = levels.findByProductIdOrderByWarehouseId(productId).stream()
                .filter(l -> visible.contains(l.getWarehouseId()))
                .map(l -> new WarehouseStock(l.getWarehouseId(), labels.get(l.getWarehouseId()), l.getQtyOnHand(),
                        batches.findForPick(productId, l.getWarehouseId()).stream()
                                .map(b -> batchRow(b, p, labels, today)).toList()))
                .toList();
        BigDecimal total = rows.stream().map(WarehouseStock::qtyOnHand).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProductStock(p.getId(), p.getNameEn(), total, rows);
    }

    @Operation(summary = "Stock card (ledger)", description = "Every movement, newest first. from/to are dates in Phnom Penh time.")
    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public PageResponse<MovementRow> stockMovements(@RequestParam(required = false) Long productId,
                                               @RequestParam(required = false) Long warehouseId,
                                               @RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to,
                                               @ParameterObject Pageable pageable) {
        List<Long> visible = scope.warehouseIds();
        if (warehouseId != null) {
            if (!visible.contains(warehouseId)) {
                throw ApiException.forbidden("BRANCH_ACCESS_DENIED");
            }
            visible = List.of(warehouseId);
        }
        Instant start = (from == null ? LocalDate.of(2000, 1, 1) : from).atStartOfDay(Dates.PHNOM_PENH).toInstant();
        Instant end = (to == null ? Dates.today() : to).plusDays(1).atStartOfDay(Dates.PHNOM_PENH).toInstant();
        Page<StockMovement> page = movements.search(productId, visible, start, end,
                org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        Map<Long, Product> byId = productService.byIds(page.getContent().stream().map(StockMovement::getProductId).toList());
        Map<Long, String> labels = branches.warehouseLabels();
        return PageResponse.of(page, m -> new MovementRow(m.getId(), m.getCreatedAt(), m.getProductId(),
                byId.get(m.getProductId()).getNameEn(), m.getWarehouseId(), labels.get(m.getWarehouseId()),
                m.getBatchId(), m.getType(), m.getQty(), m.getBalanceAfter(), m.getReferenceType(), m.getReferenceId()));
    }

    @Operation(summary = "Recalculate reorder points now",
            description = "PLAIN.md 11.9: done every morning at 06:50 for every business; this runs it now for the open one "
                    + "(after an import or a big change). Returns how many products have a point from their sales history.")
    @org.springframework.web.bind.annotation.PostMapping("/reorder-points/recalculate")
    @PreAuthorize("hasAuthority('product:manage')")
    public java.util.Map<String, Integer> recalculateReorderPoints() {
        return java.util.Map.of("products", reorderPoints.recalculate(com.modernstock.business.BusinessContext.businessId()));
    }

    @Operation(summary = "Batches expiring soon", description = "Batches in stock that expire within N days, expired ones included; "
            + "in one warehouse with warehouseId. A large N (3650) lists every batch with an expiry date.")
    @GetMapping("/batches")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public List<BatchRow> expiringBatches(@Parameter(example = "30") @RequestParam(defaultValue = "30") int expiringInDays,
                                   @RequestParam(required = false) Long productId,
                                   @RequestParam(required = false) Long warehouseId) {
        LocalDate today = Dates.today();
        List<StockBatch> list = batches.findExpiring(today.plusDays(Math.min(expiringInDays, 36500)), oneOrAll(warehouseId), productId);
        Map<Long, Product> byId = productService.byIds(list.stream().map(StockBatch::getProductId).toList());
        Map<Long, String> labels = branches.warehouseLabels();
        return list.stream().map(b -> batchRow(b, byId.get(b.getProductId()), labels, today)).toList();
    }

    @Operation(summary = "Numbers for the dashboard", description = "Stock value at average cost, in transit, low stock and expiry counts.")
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public StockSummary stockSummary() {
        List<Long> visible = scope.warehouseIds();
        List<Product> all = products.findAll();
        Map<Long, BigDecimal> onHand = productService.onHand(all.stream().map(Product::getId).toList(), visible);
        BigDecimal value = all.stream()
                .map(p -> onHand.getOrDefault(p.getId(), BigDecimal.ZERO).multiply(p.getAvgCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        Map<Long, BigDecimal> reorderAt = reorderLevels.inScope(all);
        long low = all.stream().filter(p -> p.isActive() && reorderAt.get(p.getId()).signum() > 0
                && onHand.getOrDefault(p.getId(), BigDecimal.ZERO).compareTo(reorderAt.get(p.getId())) <= 0).count();
        LocalDate today = Dates.today();
        int soonDays = expiryWindows.current().get(0);
        List<StockBatch> soon = batches.findExpiring(today.plusDays(soonDays), visible, null);
        long expired = soon.stream().filter(b -> b.getExpiryDate().isBefore(today)).count();
        BigDecimal inTransit = BusinessContext.branchFilter() == null
                ? transfers.inTransitValue().setScale(2, RoundingMode.HALF_UP) : null;
        return new StockSummary(value, inTransit, low, soon.size() - expired, soonDays, expired);
    }

    private StockRow row(Product p, BigDecimal qty, BigDecimal reorderAt, Map<Long, String> symbols, BigDecimal reserved) {
        boolean showCost = BusinessContext.has("product:manage");
        return new StockRow(p.getId(), p.getSku(), p.getNameEn(), p.getNameKm(), symbols.get(p.getBaseUnitId()), qty,
                reorderAt, showCost ? qty.multiply(p.getAvgCost()).setScale(2, RoundingMode.HALF_UP) : null,
                reorderAt.signum() > 0 && qty.compareTo(reorderAt) <= 0, reserved);
    }

    private static BatchRow batchRow(StockBatch b, Product p, Map<Long, String> labels, LocalDate today) {
        return new BatchRow(b.getId(), b.getProductId(), p == null ? null : p.getNameEn(), b.getWarehouseId(),
                labels.get(b.getWarehouseId()), b.getBatchNo(), b.getExpiryDate(), b.getQtyOnHand(),
                b.getExpiryDate() != null && b.getExpiryDate().isBefore(today));
    }

    private static String like(String q) {
        return q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase() + "%";
    }

    /** The warehouses a request covers: the one asked for (when the member sees it), else all they see. */
    private List<Long> oneOrAll(Long warehouseId) {
        List<Long> visible = scope.warehouseIds();
        if (warehouseId == null) {
            return visible;
        }
        if (!visible.contains(warehouseId)) {
            throw ApiException.forbidden("BRANCH_ACCESS_DENIED");
        }
        return List.of(warehouseId);
    }
}
