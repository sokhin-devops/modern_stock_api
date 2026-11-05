package com.modernstock.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.business.Branch;
import com.modernstock.business.BranchRepository;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BranchService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.Warehouse;
import com.modernstock.business.WarehouseRepository;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductRepository;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Stock counts (PLAIN.md 11.2, FR-4.4, FR-4.9). Starting a count takes the system quantity of each product in one
 * warehouse or in every warehouse of a branch: all products, one category, or the products chosen (one shelf). Staff
 * enter what they counted, typed or scanned; in a blind count they don't see the system quantity until it is submitted.
 * A product whose stock moves after its snapshot (sold or received during the count) is flagged and counted again.
 * Owner or Manager (adjustment:approve) posts: each difference is added or removed as a COUNT_ADJUST movement, valued at
 * the average cost of the snapshot. Products left uncounted are not changed.
 */
@Service
@RequiredArgsConstructor
public class StockCountService {

    public record CountRequest(
            @Schema(description = "One warehouse; empty = every warehouse of the branch") Long warehouseId,
            @Schema(description = "With no warehouse: the branch (default: the one picked in the top bar)") Long branchId,
            @Schema(description = "Count one category") Long categoryId,
            @Schema(description = "Count only these products, e.g. one shelf") @Size(max = 500) List<Long> productIds,
            @Schema(description = "Counters don't see the system quantity until the count is submitted") boolean blind,
            @Size(max = 500) String note) {
    }

    public record CountedLine(@NotNull Long itemId, @DecimalMin("0") BigDecimal countedQty) {
    }

    public record CountedRequest(@NotNull List<@Valid CountedLine> lines) {
    }

    public record RecountRequest(@Schema(description = "Lines to count again; empty = every flagged line") List<Long> itemIds) {
    }

    /** A barcode that counts the product, and the base units one scan adds (24 for a carton of 24). */
    public record Code(String barcode, BigDecimal factor) {
    }

    public record CountLineResponse(Long id, Long productId, String productName, String productNameKm, String sku, String unitSymbol,
                                    Long warehouseId, String warehouseName,
                                    @Schema(description = "Empty in a blind count until it is submitted") BigDecimal systemQty,
                                    BigDecimal countedQty, BigDecimal difference, BigDecimal value,
                                    @Schema(description = "Stock moved after the snapshot (sold or received during the count): count it again")
                                    boolean moved,
                                    List<Code> codes) {
    }

    public record CountResponse(Long id, String number, Long branchId,
                                @Schema(description = "Empty = every warehouse of the branch") Long warehouseId,
                                @Schema(description = "The warehouse, or the branch when the count covers all its warehouses") String warehouseName,
                                Long categoryId, StockCount.Scope scope, boolean blind, StockCount.Status status, String note,
                                Instant createdAt, Instant submittedAt, Instant postedAt, int lineCount, int counted,
                                @Schema(description = "Empty in a blind count until it is submitted") Integer differences,
                                @Schema(description = "Empty in a blind count until it is submitted") BigDecimal differenceValue,
                                @Schema(description = "Counted lines flagged for a recount") int moved,
                                List<CountLineResponse> lines) {
    }

    static final String REF = "STOCK_COUNT";

    private final StockCountRepository counts;
    private final WarehouseRepository warehouses;
    private final BranchRepository branches;
    private final BranchService branchService;
    private final ProductRepository products;
    private final ProductService productService;
    private final StockService stock;
    private final StockMovementRepository movements;
    private final DocumentNumberService numbers;
    private final BranchScope scope;
    private final ActivityService activity;

    @Transactional(readOnly = true)
    public PageResponse<CountResponse> list(StockCount.Status status, Pageable pageable) {
        return PageResponse.of(counts.search(scope.branchIds(), status, pageable), c -> toResponse(c, false));
    }

    @Transactional(readOnly = true)
    public CountResponse get(Long id) {
        StockCount c = counts.get(id);
        BusinessContext.requireBranch(c.getBranchId());
        return toResponse(c, true);
    }

    @Transactional
    public CountResponse start(CountRequest req) {
        Long branchId;
        List<Warehouse> where;
        if (req.warehouseId() != null) {
            Warehouse w = warehouses.get(req.warehouseId());
            branchId = BusinessContext.branchForWrite(w.getBranchId());
            where = List.of(w);
        } else {
            branchId = BusinessContext.branchForWrite(req.branchId());
            where = warehouses.findByBranchIdAndActiveTrue(branchId);
        }
        StockCount.Scope what;
        List<Product> list;
        if (req.productIds() != null && !req.productIds().isEmpty()) {
            what = StockCount.Scope.PRODUCTS;
            list = new LinkedHashSet<>(req.productIds()).stream().map(products::get).filter(Product::isActive).toList();
        } else {
            what = req.categoryId() != null ? StockCount.Scope.CATEGORY : StockCount.Scope.ALL;
            list = products.search("%", req.categoryId(), true, Pageable.unpaged()).getContent();
        }
        if (list.isEmpty() || where.isEmpty()) {
            throw ApiException.rule("COUNT_NOTHING_TO_COUNT");
        }

        StockCount c = new StockCount();
        c.setNumber(numbers.next(DocType.CNT));
        c.setBranchId(branchId);
        c.setWarehouseId(req.warehouseId());
        c.setCategoryId(what == StockCount.Scope.CATEGORY ? req.categoryId() : null);
        c.setScope(what);
        c.setBlind(req.blind());
        c.setNote(req.note());
        Instant now = Instant.now();
        List<Long> ids = list.stream().map(Product::getId).toList();
        for (Warehouse w : where.stream().sorted(Comparator.comparing(Warehouse::getId)).toList()) {
            Map<Long, BigDecimal> onHand = productService.onHand(ids, List.of(w.getId()));
            list.stream().sorted(Comparator.comparing(Product::getNameEn)).forEach(p -> {
                StockCountItem item = new StockCountItem();
                item.setProductId(p.getId());
                item.setWarehouseId(w.getId());
                item.setSystemQty(onHand.getOrDefault(p.getId(), BigDecimal.ZERO));
                item.setSnapshotAt(now);
                item.setUnitCost(p.getAvgCost() == null ? BigDecimal.ZERO : p.getAvgCost());
                c.getItems().add(item);
            });
        }
        return toResponse(counts.save(c), true);
    }

    @Transactional
    public CountResponse saveCounted(Long id, CountedRequest req) {
        StockCount c = inStatus(id, StockCount.Status.DRAFT);
        Map<Long, StockCountItem> byId = c.getItems().stream().collect(Collectors.toMap(StockCountItem::getId, i -> i));
        for (CountedLine line : req.lines()) {
            StockCountItem item = byId.get(line.itemId());
            if (item == null) {
                throw ApiException.notFound("Stock count line");
            }
            item.setCountedQty(line.countedQty());
        }
        return toResponse(c, true);
    }

    /** Takes the system quantity again for lines whose stock moved, and clears what was counted for them. */
    @Transactional
    public CountResponse recount(Long id, RecountRequest req) {
        StockCount c = inStatus(id, StockCount.Status.DRAFT);
        Set<Long> which = req == null || req.itemIds() == null || req.itemIds().isEmpty()
                ? movedItems(c) : new HashSet<>(req.itemIds());
        List<StockCountItem> again = c.getItems().stream().filter(i -> which.contains(i.getId())).toList();
        Map<Long, Product> byId = productService.byIds(again.stream().map(StockCountItem::getProductId).toList());
        Instant now = Instant.now();
        again.stream().collect(Collectors.groupingBy(StockCountItem::getWarehouseId)).forEach((warehouseId, items) -> {
            Map<Long, BigDecimal> onHand = productService.onHand(items.stream().map(StockCountItem::getProductId).toList(),
                    List.of(warehouseId));
            for (StockCountItem item : items) {
                Product p = byId.get(item.getProductId());
                item.setSystemQty(onHand.getOrDefault(item.getProductId(), BigDecimal.ZERO));
                item.setUnitCost(p.getAvgCost() == null ? BigDecimal.ZERO : p.getAvgCost());
                item.setSnapshotAt(now);
                item.setCountedQty(null);
            }
        });
        return toResponse(c, true);
    }

    /** The counter is done: Owner or Manager checks the differences and posts. */
    @Transactional
    public CountResponse submit(Long id) {
        StockCount c = inStatus(id, StockCount.Status.DRAFT);
        readyToPost(c);
        c.setStatus(StockCount.Status.SUBMITTED);
        c.setSubmittedAt(Instant.now());
        return toResponse(c, true);
    }

    /** Back to the counter, e.g. to count something again. */
    @Transactional
    public CountResponse reopen(Long id) {
        StockCount c = inStatus(id, StockCount.Status.SUBMITTED);
        c.setStatus(StockCount.Status.DRAFT);
        c.setSubmittedAt(null);
        return toResponse(c, true);
    }

    @Transactional
    public CountResponse post(Long id) {
        StockCount c = counts.get(id);
        BusinessContext.requireBranch(c.getBranchId());
        if (c.getStatus() != StockCount.Status.SUBMITTED && (c.getStatus() != StockCount.Status.DRAFT || c.isBlind())) {
            throw c.getStatus() == StockCount.Status.DRAFT ? ApiException.rule("COUNT_BLIND_SUBMIT_FIRST")
                    : ApiException.rule("WRONG_STATUS", c.getStatus());
        }
        readyToPost(c);
        StockService.Ref ref = new StockService.Ref(REF, c.getId());
        Map<Long, Product> byId = productService.byIds(c.getItems().stream().map(StockCountItem::getProductId).toList());
        c.getItems().stream()
                .sorted(Comparator.comparing(StockCountItem::getProductId).thenComparing(StockCountItem::getWarehouseId))
                .forEach(item -> {
                    if (item.getCountedQty() == null) {
                        return;
                    }
                    BigDecimal diff = item.getCountedQty().subtract(item.getSystemQty());
                    Product p = byId.get(item.getProductId());
                    if (diff.signum() > 0) {
                        stock.receive(p, item.getWarehouseId(), diff, item.getUnitCost(), null, null, StockMovement.Type.COUNT_ADJUST, ref);
                    } else if (diff.signum() < 0) {
                        stock.issue(p, item.getWarehouseId(), diff.negate(), StockMovement.Type.COUNT_ADJUST, ref, true);
                    }
                });
        c.setStatus(StockCount.Status.POSTED);
        c.setPostedAt(Instant.now());
        CountResponse posted = toResponse(c, true);
        activity.record("COUNT_POSTED", "STOCK_COUNT", c.getId(), c.getNumber(), c.getBranchId(), null,
                ActivityService.values("differences", posted.differences(), "value", posted.differenceValue()));
        return posted;
    }

    /** A draft count can be thrown away; nothing in stock changed yet. */
    @Transactional
    public void delete(Long id) {
        counts.delete(inStatus(id, StockCount.Status.DRAFT));
    }

    /** 422 COUNT_EMPTY when nothing was counted, RECOUNT_NEEDED while a counted product moved after its snapshot. */
    private void readyToPost(StockCount c) {
        if (c.getItems().stream().allMatch(i -> i.getCountedQty() == null)) {
            throw ApiException.rule("COUNT_EMPTY");
        }
        Set<Long> moved = movedItems(c);
        long counted = c.getItems().stream().filter(i -> i.getCountedQty() != null && moved.contains(i.getId())).count();
        if (counted > 0) {
            throw ApiException.rule("RECOUNT_NEEDED", counted);
        }
    }

    /** Lines whose product went in or out of their warehouse after the snapshot, other than by this count. */
    private Set<Long> movedItems(StockCount c) {
        if (c.getItems().isEmpty()) {
            return Set.of();
        }
        Instant since = c.getItems().stream().map(StockCountItem::getSnapshotAt).min(Comparator.naturalOrder()).orElseThrow();
        Map<String, Instant> last = new HashMap<>();
        for (Object[] row : movements.lastMoves(c.getItems().stream().map(StockCountItem::getWarehouseId).collect(Collectors.toSet()),
                c.getItems().stream().map(StockCountItem::getProductId).collect(Collectors.toSet()), since, REF)) {
            last.put(row[0] + "-" + row[1], (Instant) row[2]);
        }
        return c.getItems().stream()
                .filter(i -> {
                    Instant at = last.get(i.getProductId() + "-" + i.getWarehouseId());
                    return at != null && at.isAfter(i.getSnapshotAt());
                })
                .map(StockCountItem::getId).collect(Collectors.toSet());
    }

    private StockCount inStatus(Long id, StockCount.Status status) {
        StockCount c = counts.get(id);
        BusinessContext.requireBranch(c.getBranchId());
        if (c.getStatus() != status) {
            throw ApiException.rule("WRONG_STATUS", c.getStatus());
        }
        return c;
    }

    private CountResponse toResponse(StockCount c, boolean withLines) {
        boolean hidden = c.isBlind() && c.getStatus() == StockCount.Status.DRAFT;
        boolean open = c.getStatus() == StockCount.Status.DRAFT || c.getStatus() == StockCount.Status.SUBMITTED;
        Set<Long> moved = open ? movedItems(c) : Set.of();
        Map<Long, String> labels = branchService.warehouseLabels();
        String where = c.getWarehouseId() != null ? labels.get(c.getWarehouseId())
                : branches.findById(c.getBranchId()).map(Branch::getNameEn).orElse("");
        List<CountLineResponse> lines = new ArrayList<>();
        if (withLines) {
            // The count is in one branch, so a line names only its warehouse
            Map<Long, String> warehouseNames = warehouses.findAllById(c.getItems().stream().map(StockCountItem::getWarehouseId)
                    .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Warehouse::getId, Warehouse::getNameEn));
            Map<Long, Product> byId = productService.byIds(c.getItems().stream().map(StockCountItem::getProductId).toList());
            Map<Long, String> symbols = productService.unitSymbols();
            for (StockCountItem i : c.getItems()) {
                Product p = byId.get(i.getProductId());
                BigDecimal diff = i.getCountedQty() == null ? null : i.getCountedQty().subtract(i.getSystemQty());
                BigDecimal value = diff == null ? null : diff.multiply(i.getUnitCost()).setScale(2, RoundingMode.HALF_UP);
                lines.add(new CountLineResponse(i.getId(), i.getProductId(), p == null ? "" : p.getNameEn(), p == null ? null : p.getNameKm(),
                        p == null ? "" : p.getSku(), p == null ? "" : symbols.get(p.getBaseUnitId()), i.getWarehouseId(),
                        warehouseNames.get(i.getWarehouseId()), hidden ? null : i.getSystemQty(), i.getCountedQty(), hidden ? null : diff,
                        hidden ? null : value, moved.contains(i.getId()), p == null ? List.of() : codes(p)));
            }
        }
        int counted = (int) c.getItems().stream().filter(i -> i.getCountedQty() != null).count();
        int differences = (int) c.getItems().stream()
                .filter(i -> i.getCountedQty() != null && i.getCountedQty().compareTo(i.getSystemQty()) != 0).count();
        BigDecimal value = c.getItems().stream().filter(i -> i.getCountedQty() != null)
                .map(i -> i.getCountedQty().subtract(i.getSystemQty()).multiply(i.getUnitCost()).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int movedCounted = (int) c.getItems().stream().filter(i -> i.getCountedQty() != null && moved.contains(i.getId())).count();
        return new CountResponse(c.getId(), c.getNumber(), c.getBranchId(), c.getWarehouseId(), where, c.getCategoryId(), c.getScope(),
                c.isBlind(), c.getStatus(), c.getNote(), c.getCreatedAt(), c.getSubmittedAt(), c.getPostedAt(), c.getItems().size(), counted,
                hidden ? null : differences, hidden ? null : value, movedCounted, lines);
    }

    private static List<Code> codes(Product p) {
        List<Code> codes = new ArrayList<>();
        if (p.getBarcode() != null && !p.getBarcode().isBlank()) {
            codes.add(new Code(p.getBarcode(), BigDecimal.ONE));
        }
        p.getUnits().stream().filter(u -> u.getBarcode() != null && !u.getBarcode().isBlank())
                .forEach(u -> codes.add(new Code(u.getBarcode(), u.getFactor())));
        return codes;
    }
}
