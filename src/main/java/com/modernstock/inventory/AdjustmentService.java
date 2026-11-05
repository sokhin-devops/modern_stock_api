package com.modernstock.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
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
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.PageResponse;
import com.modernstock.notification.TelegramAlerts;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Opening stock and adjustments. The Owner and Manager post them directly; a stock keeper's request
 * waits for approval (PLAIN.md 11.9). Losses are valued at average cost.
 */
@Service
@RequiredArgsConstructor
public class AdjustmentService {

    public static final String REF = "ADJUSTMENT";

    public record AdjustmentLineRequest(
            @NotNull Long productId,
            @Schema(description = "Unit of qtyChange; empty = base unit") Long unitId,
            @Schema(description = "+ adds stock, − removes it", example = "-2") @NotNull BigDecimal qtyChange,
            @Schema(description = "Opening stock only: cost of one unitId") @DecimalMin("0") BigDecimal unitCost,
            @Schema(description = "Remove from this batch; empty = earliest expiry first") Long batchId,
            @Size(max = 40) String batchNo,
            @Schema(description = "Required when adding stock of a product that tracks expiry") LocalDate expiryDate) {
    }

    public record AdjustmentRequest(
            @NotNull Long warehouseId,
            @NotNull StockAdjustment.Kind kind,
            @Schema(description = "Required for ADJUSTMENT. LOST_IN_TRANSIT is set only by transfers")
            StockAdjustment.Reason reason,
            @Size(max = 500) String note,
            @NotEmpty List<@Valid AdjustmentLineRequest> lines) {
    }

    public record AdjustmentLineResponse(Long id, Long productId, String productName, Long batchId, String batchNo,
                                         LocalDate expiryDate, BigDecimal qtyChange, BigDecimal unitCost,
                                         BigDecimal value) {
    }

    public record AdjustmentResponse(Long id, String number, Long branchId, Long warehouseId, String warehouseName,
                                     StockAdjustment.Kind kind, StockAdjustment.Reason reason,
                                     StockAdjustment.Status status, Instant postedAt, Long approvedBy, String note,
                                     Instant createdAt, BigDecimal totalValue, List<AdjustmentLineResponse> lines) {
    }

    /** A shortfall found when receiving a transfer. */
    public record Loss(Product product, Long batchId, BigDecimal qty, BigDecimal unitCost) {
    }

    private final StockAdjustmentRepository adjustments;
    private final ActivityService activity;
    private final WarehouseRepository warehouses;
    private final ProductService products;
    private final StockService stock;
    private final DocumentNumberService numbers;
    private final BranchService branches;
    private final BranchScope scope;
    private final ApplicationEventPublisher events;

    @Transactional
    public AdjustmentResponse create(AdjustmentRequest req) {
        Warehouse w = warehouses.get(req.warehouseId());
        BusinessContext.requireBranch(w.getBranchId());
        boolean opening = req.kind() == StockAdjustment.Kind.OPENING;
        StockAdjustment.Reason reason = opening ? StockAdjustment.Reason.OPENING : req.reason();
        if (!opening && (reason == null || reason == StockAdjustment.Reason.OPENING
                || reason == StockAdjustment.Reason.LOST_IN_TRANSIT)) {
            throw ApiException.badRequest("VALIDATION_FAILED");
        }
        boolean canPost = BusinessContext.has(opening ? "stock:opening" : "adjustment:post");
        if (!canPost && (opening || !BusinessContext.has("adjustment:request"))) {
            throw ApiException.forbidden("PERMISSION_DENIED");
        }

        StockAdjustment adj = new StockAdjustment();
        adj.setNumber(numbers.next(DocType.ADJ));
        adj.setBranchId(w.getBranchId());
        adj.setWarehouseId(w.getId());
        adj.setKind(req.kind());
        adj.setReason(reason);
        adj.setNote(req.note());
        adj.setStatus(canPost ? StockAdjustment.Status.POSTED : StockAdjustment.Status.WAITING_APPROVAL);
        for (AdjustmentLineRequest line : req.lines()) {
            Product p = products.active(line.productId());
            BigDecimal factor = products.factor(p, line.unitId());
            BigDecimal qty = line.qtyChange().multiply(factor);
            if (qty.signum() == 0 || (opening && qty.signum() < 0)) {
                throw ApiException.badRequest("QTY_MUST_BE_POSITIVE");
            }
            if (qty.signum() > 0 && p.isTrackExpiry() && line.expiryDate() == null) {
                throw ApiException.rule("EXPIRY_REQUIRED", p.getNameEn());
            }
            StockAdjustmentItem item = new StockAdjustmentItem();
            item.setProductId(p.getId());
            item.setQtyChange(qty);
            item.setBatchId(line.batchId());
            item.setBatchNo(line.batchNo());
            item.setExpiryDate(line.expiryDate());
            item.setUnitCost(opening
                    ? (line.unitCost() == null ? BigDecimal.ZERO : line.unitCost()).divide(factor, 4, RoundingMode.HALF_UP)
                    : p.getAvgCost());
            adj.getItems().add(item);
        }
        adjustments.save(adj);
        if (canPost) {
            adj = post(adj);
            logPosted("ADJUSTMENT_POSTED", adj);
        } else {
            events.publishEvent(new TelegramAlerts.ApprovalWaiting(BusinessContext.businessId(), TelegramAlerts.Waiting.ADJUSTMENT,
                    adj.getNumber(), adj.getBranchId(), CurrentUser.id(), null));
        }
        return toResponse(adj);
    }

    @Transactional
    public AdjustmentResponse approve(Long id) {
        StockAdjustment adj = waiting(id);
        adj.setStatus(StockAdjustment.Status.POSTED);
        adj = post(adj);
        logPosted("ADJUSTMENT_APPROVED", adj);
        return toResponse(adj);
    }

    @Transactional
    public AdjustmentResponse reject(Long id) {
        StockAdjustment adj = waiting(id);
        adj.setStatus(StockAdjustment.Status.REJECTED);
        logPosted("ADJUSTMENT_REJECTED", adj);
        return toResponse(adj);
    }

    /** The reason and the value of the stock that came in (+) or went out (-). */
    /**
     * Voids a posted adjustment or opening stock (PLAIN.md 11.1): the opposite movements, back into the same batches,
     * while what it added is still there. A "lost in transit" adjustment goes with its transfer (void the transfer).
     */
    @Transactional
    public AdjustmentResponse voidAdjustment(Long id) {
        StockAdjustment adj = adjustments.lockById(id).orElseThrow(() -> ApiException.notFound("Adjustment"));
        BusinessContext.requireBranch(adj.getBranchId());
        if (adj.getStatus() != StockAdjustment.Status.POSTED) {
            throw ApiException.rule("WRONG_STATUS", adj.getStatus());
        }
        if (adj.getReason() == StockAdjustment.Reason.LOST_IN_TRANSIT) {
            throw ApiException.rule("ADJUSTMENT_FROM_TRANSFER");
        }
        StockService.Ref ref = new StockService.Ref(REF, adj.getId());
        stock.requireStillThere(ref);
        stock.reverse(ref);
        adj.setStatus(StockAdjustment.Status.VOID);
        adj.setVoidedAt(Instant.now());
        adj.setVoidedBy(CurrentUser.id());
        logPosted("ADJUSTMENT_VOIDED", adj);
        return toResponse(adj);
    }

    private void logPosted(String action, StockAdjustment adj) {
        BigDecimal value = adj.getItems().stream().map(i -> i.getQtyChange().multiply(i.getUnitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        activity.record(action, "ADJUSTMENT", adj.getId(), adj.getNumber(), adj.getBranchId(), null,
                ActivityService.values("reason", adj.getReason().name(), "value", value));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdjustmentResponse> list(StockAdjustment.Status status, Pageable pageable) {
        return PageResponse.of(adjustments.search(scope.branchIds(), status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public AdjustmentResponse get(Long id) {
        StockAdjustment adj = adjustments.get(id);
        BusinessContext.requireBranch(adj.getBranchId());
        return toResponse(adj);
    }

    /** The automatic "lost in transit" adjustment when less arrives than was sent (PLAIN.md 11.9). */
    @Transactional
    public StockAdjustment postLostInTransit(Warehouse destination, List<Loss> losses, String transferNumber) {
        StockAdjustment adj = new StockAdjustment();
        adj.setNumber(numbers.next(DocType.ADJ));
        adj.setBranchId(destination.getBranchId());
        adj.setWarehouseId(destination.getId());
        adj.setKind(StockAdjustment.Kind.ADJUSTMENT);
        adj.setReason(StockAdjustment.Reason.LOST_IN_TRANSIT);
        adj.setStatus(StockAdjustment.Status.POSTED);
        adj.setNote("Shortfall when receiving " + transferNumber);
        adj.setPostedAt(Instant.now());
        adj.setApprovedBy(CurrentUser.idOrNull());
        for (Loss loss : losses) {
            StockAdjustmentItem item = new StockAdjustmentItem();
            item.setProductId(loss.product().getId());
            item.setBatchId(loss.batchId());
            item.setQtyChange(loss.qty().negate());
            item.setUnitCost(loss.unitCost());
            adj.getItems().add(item);
        }
        adjustments.save(adj);
        StockService.Ref ref = new StockService.Ref(REF, adj.getId());
        for (Loss loss : losses) {
            stock.issueFromBatch(loss.product(), loss.batchId(), loss.qty(), StockMovement.Type.ADJUSTMENT, ref);
        }
        return adj;
    }

    /** Returns the adjustment to go on with: a long one comes back as a new instance (see StockService.lighten). */
    private StockAdjustment post(StockAdjustment adj) {
        StockService.Ref ref = new StockService.Ref(REF, adj.getId());
        StockMovement.Type type = adj.getKind() == StockAdjustment.Kind.OPENING
                ? StockMovement.Type.OPENING : StockMovement.Type.ADJUSTMENT;
        List<StockAdjustmentItem> sorted = adj.getItems().stream()
                .sorted(Comparator.comparing(StockAdjustmentItem::getProductId)).toList();
        boolean detached = false;
        int done = 0;
        for (StockAdjustmentItem item : sorted) {
            Product p = products.active(item.getProductId());
            if (item.getQtyChange().signum() > 0) {
                StockBatch batch = stock.receive(p, adj.getWarehouseId(), item.getQtyChange(), item.getUnitCost(),
                        item.getBatchNo(), item.getExpiryDate(), type, ref);
                item.setBatchId(batch.getId());
            } else if (item.getBatchId() != null) {
                stock.issueFromBatch(p, item.getBatchId(), item.getQtyChange().negate(), type, ref);
            } else {
                // adjustments may take expired batches: that is how expired goods leave stock
                List<StockService.Pick> picks = stock.issue(p, adj.getWarehouseId(), item.getQtyChange().negate(),
                        type, ref, true);
                if (picks.size() == 1) {
                    item.setBatchId(picks.get(0).batchId());
                    item.setBatchNo(picks.get(0).batchNo());
                    item.setExpiryDate(picks.get(0).expiryDate());
                }
            }
            detached |= stock.lighten(++done);
        }
        adj.setPostedAt(Instant.now());
        adj.setApprovedBy(CurrentUser.idOrNull());
        return detached ? stock.reattach(adj) : adj;
    }

    private StockAdjustment waiting(Long id) {
        StockAdjustment adj = adjustments.get(id);
        BusinessContext.requireBranch(adj.getBranchId());
        if (adj.getStatus() != StockAdjustment.Status.WAITING_APPROVAL) {
            throw ApiException.rule("WRONG_STATUS", adj.getStatus());
        }
        return adj;
    }

    private AdjustmentResponse toResponse(StockAdjustment a) {
        Map<Long, Product> byId = products.byIds(a.getItems().stream().map(StockAdjustmentItem::getProductId).toList());
        List<AdjustmentLineResponse> lines = a.getItems().stream().map(i -> {
            BigDecimal value = i.getQtyChange().multiply(i.getUnitCost()).setScale(2, RoundingMode.HALF_UP);
            return new AdjustmentLineResponse(i.getId(), i.getProductId(), byId.get(i.getProductId()).getNameEn(),
                    i.getBatchId(), i.getBatchNo(), i.getExpiryDate(), i.getQtyChange(), i.getUnitCost(), value);
        }).toList();
        BigDecimal total = lines.stream().map(AdjustmentLineResponse::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AdjustmentResponse(a.getId(), a.getNumber(), a.getBranchId(), a.getWarehouseId(),
                branches.warehouseLabels().get(a.getWarehouseId()), a.getKind(), a.getReason(), a.getStatus(),
                a.getPostedAt(), a.getApprovedBy(), a.getNote(), a.getCreatedAt(), total, lines);
    }
}
