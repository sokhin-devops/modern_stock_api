package com.modernstock.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.Warehouse;
import com.modernstock.business.WarehouseRepository;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductRepository;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Stock counts (PLAIN.md 11.2). Starting a count takes the system quantity of every active product in the warehouse
 * (or one category); staff enter what they counted; posting adds or removes the difference as COUNT_ADJUST movements,
 * valued at the average cost of the start. Products left uncounted are not changed.
 */
@Service
@RequiredArgsConstructor
public class StockCountService {

    public record CountRequest(@NotNull Long warehouseId, Long categoryId, @Size(max = 500) String note) {
    }

    public record CountedLine(@NotNull Long itemId, @DecimalMin("0") BigDecimal countedQty) {
    }

    public record CountedRequest(@NotNull List<@Valid CountedLine> lines) {
    }

    public record CountLineResponse(Long id, Long productId, String productName, String sku, String unitSymbol,
                                    BigDecimal systemQty, BigDecimal countedQty, BigDecimal difference, BigDecimal value) {
    }

    public record CountResponse(Long id, String number, Long branchId, Long warehouseId, String warehouseName, Long categoryId,
                                StockCount.Status status, String note, Instant createdAt, Instant postedAt, int counted,
                                int differences, BigDecimal differenceValue, List<CountLineResponse> lines) {
    }

    static final String REF = "STOCK_COUNT";

    private final StockCountRepository counts;
    private final WarehouseRepository warehouses;
    private final ProductRepository products;
    private final ProductService productService;
    private final StockService stock;
    private final DocumentNumberService numbers;
    private final BranchScope scope;

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
        Warehouse w = warehouses.get(req.warehouseId());
        BusinessContext.requireBranch(w.getBranchId());
        List<Product> list = products.search("%", req.categoryId(), true, Pageable.unpaged()).getContent();
        Map<Long, BigDecimal> onHand = productService.onHand(list.stream().map(Product::getId).toList(), List.of(w.getId()));

        StockCount c = new StockCount();
        c.setNumber(numbers.next(DocType.CNT));
        c.setBranchId(w.getBranchId());
        c.setWarehouseId(w.getId());
        c.setCategoryId(req.categoryId());
        c.setNote(req.note());
        list.stream().sorted(Comparator.comparing(Product::getNameEn)).forEach(p -> {
            StockCountItem item = new StockCountItem();
            item.setProductId(p.getId());
            item.setSystemQty(onHand.getOrDefault(p.getId(), BigDecimal.ZERO));
            item.setUnitCost(p.getAvgCost() == null ? BigDecimal.ZERO : p.getAvgCost());
            c.getItems().add(item);
        });
        return toResponse(counts.save(c), true);
    }

    @Transactional
    public CountResponse saveCounted(Long id, CountedRequest req) {
        StockCount c = draft(id);
        Map<Long, StockCountItem> byId = c.getItems().stream()
                .collect(java.util.stream.Collectors.toMap(StockCountItem::getId, i -> i));
        for (CountedLine line : req.lines()) {
            StockCountItem item = byId.get(line.itemId());
            if (item == null) {
                throw ApiException.notFound("Stock count line");
            }
            item.setCountedQty(line.countedQty());
        }
        return toResponse(c, true);
    }

    @Transactional
    public CountResponse post(Long id) {
        StockCount c = draft(id);
        StockService.Ref ref = new StockService.Ref(REF, c.getId());
        Map<Long, Product> byId = productService.byIds(c.getItems().stream().map(StockCountItem::getProductId).toList());
        c.getItems().stream().sorted(Comparator.comparing(StockCountItem::getProductId)).forEach(item -> {
            if (item.getCountedQty() == null) {
                return;
            }
            BigDecimal diff = item.getCountedQty().subtract(item.getSystemQty());
            Product p = byId.get(item.getProductId());
            if (diff.signum() > 0) {
                stock.receive(p, c.getWarehouseId(), diff, item.getUnitCost(), null, null, StockMovement.Type.COUNT_ADJUST, ref);
            } else if (diff.signum() < 0) {
                stock.issue(p, c.getWarehouseId(), diff.negate(), StockMovement.Type.COUNT_ADJUST, ref, true);
            }
        });
        c.setStatus(StockCount.Status.POSTED);
        c.setPostedAt(Instant.now());
        return toResponse(c, true);
    }

    /** A draft count can be thrown away; nothing in stock changed yet. */
    @Transactional
    public void delete(Long id) {
        counts.delete(draft(id));
    }

    private StockCount draft(Long id) {
        StockCount c = counts.get(id);
        BusinessContext.requireBranch(c.getBranchId());
        if (c.getStatus() != StockCount.Status.DRAFT) {
            throw ApiException.rule("WRONG_STATUS", c.getStatus());
        }
        return c;
    }

    private CountResponse toResponse(StockCount c, boolean withLines) {
        Warehouse w = warehouses.get(c.getWarehouseId());
        Map<Long, Product> byId = productService.byIds(c.getItems().stream().map(StockCountItem::getProductId).toList());
        Map<Long, String> symbols = productService.unitSymbols();
        List<CountLineResponse> lines = c.getItems().stream().map(i -> {
            Product p = byId.get(i.getProductId());
            BigDecimal diff = i.getCountedQty() == null ? null : i.getCountedQty().subtract(i.getSystemQty());
            BigDecimal value = diff == null ? null : diff.multiply(i.getUnitCost()).setScale(2, RoundingMode.HALF_UP);
            return new CountLineResponse(i.getId(), i.getProductId(), p == null ? "" : p.getNameEn(), p == null ? "" : p.getSku(),
                    p == null ? "" : symbols.get(p.getBaseUnitId()), i.getSystemQty(), i.getCountedQty(), diff, value);
        }).toList();
        int counted = (int) lines.stream().filter(l -> l.countedQty() != null).count();
        int differences = (int) lines.stream().filter(l -> l.difference() != null && l.difference().signum() != 0).count();
        BigDecimal value = lines.stream().map(CountLineResponse::value).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CountResponse(c.getId(), c.getNumber(), c.getBranchId(), c.getWarehouseId(), w.getNameEn(), c.getCategoryId(),
                c.getStatus(), c.getNote(), c.getCreatedAt(), c.getPostedAt(), counted, differences, value,
                withLines ? lines : List.of());
    }
}
