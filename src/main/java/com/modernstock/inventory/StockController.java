package com.modernstock.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BranchService;
import com.modernstock.business.BusinessContext;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductRepository;
import com.modernstock.catalog.ProductService;
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
                           boolean low) {
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
                               long lowStockItems, long expiringIn30Days, long expiredBatches) {
    }

    private final ProductRepository products;
    private final ProductService productService;
    private final StockLevelRepository levels;
    private final StockBatchRepository batches;
    private final StockMovementRepository movements;
    private final StockTransferRepository transfers;
    private final BranchScope scope;
    private final BranchService branches;

    @Operation(summary = "Stock on hand per product", description = "Base units, summed over the branches you see. q matches name, SKU or barcode.")
    @GetMapping("/levels")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public PageResponse<StockRow> stockLevels(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) Long categoryId,
                                         @ParameterObject @PageableDefault(size = 20, sort = "nameEn") Pageable pageable) {
        Page<Product> page = products.search(like(q), categoryId, true, pageable);
        Map<Long, BigDecimal> onHand = productService.onHand(page.getContent().stream().map(Product::getId).toList(),
                scope.warehouseIds());
        Map<Long, String> symbols = productService.unitSymbols();
        return PageResponse.of(page, p -> row(p, onHand.getOrDefault(p.getId(), BigDecimal.ZERO), symbols));
    }

    @Operation(summary = "Low stock", description = "Products at or below their reorder level.")
    @GetMapping("/low")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public List<StockRow> lowStock() {
        List<Product> watched = products.findAll().stream()
                .filter(p -> p.isActive() && p.getReorderLevel().signum() > 0).toList();
        Map<Long, BigDecimal> onHand = productService.onHand(watched.stream().map(Product::getId).toList(),
                scope.warehouseIds());
        Map<Long, String> symbols = productService.unitSymbols();
        return watched.stream()
                .map(p -> row(p, onHand.getOrDefault(p.getId(), BigDecimal.ZERO), symbols))
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

    @Operation(summary = "Batches expiring soon", description = "Batches in stock that expire within N days, expired ones included.")
    @GetMapping("/batches")
    @PreAuthorize("hasAuthority('stock:view')")
    @Transactional(readOnly = true)
    public List<BatchRow> expiringBatches(@Parameter(example = "30") @RequestParam(defaultValue = "30") int expiringInDays,
                                   @RequestParam(required = false) Long productId) {
        LocalDate today = Dates.today();
        List<StockBatch> list = batches.findExpiring(today.plusDays(expiringInDays), scope.warehouseIds(), productId);
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
        long low = all.stream().filter(p -> p.isActive() && p.getReorderLevel().signum() > 0
                && onHand.getOrDefault(p.getId(), BigDecimal.ZERO).compareTo(p.getReorderLevel()) <= 0).count();
        LocalDate today = Dates.today();
        List<StockBatch> soon = batches.findExpiring(today.plusDays(30), visible, null);
        long expired = soon.stream().filter(b -> b.getExpiryDate().isBefore(today)).count();
        BigDecimal inTransit = BusinessContext.branchFilter() == null
                ? transfers.inTransitValue().setScale(2, RoundingMode.HALF_UP) : null;
        return new StockSummary(value, inTransit, low, soon.size() - expired, expired);
    }

    private StockRow row(Product p, BigDecimal qty, Map<Long, String> symbols) {
        boolean showCost = BusinessContext.has("product:manage");
        return new StockRow(p.getId(), p.getSku(), p.getNameEn(), p.getNameKm(), symbols.get(p.getBaseUnitId()), qty,
                p.getReorderLevel(), showCost ? qty.multiply(p.getAvgCost()).setScale(2, RoundingMode.HALF_UP) : null,
                p.getReorderLevel().signum() > 0 && qty.compareTo(p.getReorderLevel()) <= 0);
    }

    private static BatchRow batchRow(StockBatch b, Product p, Map<Long, String> labels, LocalDate today) {
        return new BatchRow(b.getId(), b.getProductId(), p == null ? null : p.getNameEn(), b.getWarehouseId(),
                labels.get(b.getWarehouseId()), b.getBatchNo(), b.getExpiryDate(), b.getQtyOnHand(),
                b.getExpiryDate() != null && b.getExpiryDate().isBefore(today));
    }

    private static String like(String q) {
        return q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase() + "%";
    }
}
