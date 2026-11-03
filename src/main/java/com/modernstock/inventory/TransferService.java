package com.modernstock.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BranchScope;
import com.modernstock.business.BranchService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.Warehouse;
import com.modernstock.business.WarehouseRepository;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductService;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.PageResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Stock transfers (PLAIN.md 11.9). One rule guides the flow: stock in transit is real money, so work that
 * has started can always be finished or undone. Transfers move stock, not cost.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    public static final String REF = "TRANSFER";

    public record TransferLineRequest(
            @NotNull Long productId,
            @Schema(description = "Empty = base unit") Long unitId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @Schema(description = "Send this batch on purpose (for example expired goods); empty = earliest expiry first")
            Long batchId) {
    }

    public record TransferRequest(
            @NotNull Long fromWarehouseId,
            @Schema(description = "A warehouse, or give toBranchId to use that branch's receiving warehouse") Long toWarehouseId,
            Long toBranchId,
            @Size(max = 255) String carrierNote,
            @Size(max = 500) String note,
            @NotEmpty List<@Valid TransferLineRequest> lines) {
    }

    public record TransferReceiveLineRequest(@NotNull Long itemId,
                                     @Schema(description = "Base units that arrived; less than sent = lost in transit")
                                     @NotNull @DecimalMin("0") BigDecimal qtyReceived) {
    }

    public record TransferReceiveRequest(
            @Schema(description = "Another warehouse of the destination branch; empty = the one chosen when sending")
            Long toWarehouseId,
            @Schema(description = "Only lines that differ; missing lines arrived in full") List<@Valid TransferReceiveLineRequest> lines) {
    }

    public record ReturnRequest(@Schema(example = "Wrong branch") @NotBlank @Size(max = 255) String reason) {
    }

    public record TransferLineResponse(Long id, Long productId, String productName, Long unitId, String unitSymbol,
                                       BigDecimal qty, BigDecimal baseQty, Long batchId, String batchNo,
                                       LocalDate expiryDate, BigDecimal unitCost, BigDecimal qtyReceived) {
    }

    public record TransferResponse(Long id, String number, StockTransfer.Kind kind, StockTransfer.Status status,
                                   Long fromWarehouseId, String fromName, Long fromBranchId,
                                   Long toWarehouseId, String toName, Long toBranchId,
                                   Instant sentAt, Instant receivedAt, Instant returnedAt, String returnReason,
                                   String carrierNote, String note,
                                   @Schema(description = "Lines that arrived short (\"Received · 1 short\")") int shortLines,
                                   Long adjustmentId, BigDecimal value, List<TransferLineResponse> lines) {
    }

    private final StockTransferRepository transfers;
    private final WarehouseRepository warehouses;
    private final StockBatchRepository batches;
    private final ProductService products;
    private final StockService stock;
    private final AdjustmentService adjustments;
    private final DocumentNumberService numbers;
    private final BranchService branches;
    private final BranchScope scope;

    @Transactional
    public TransferResponse create(TransferRequest req) {
        StockTransfer t = new StockTransfer();
        t.setNumber(numbers.next(DocType.TRF));
        t.setStatus(StockTransfer.Status.DRAFT);
        apply(t, req);
        transfers.save(t);
        return toResponse(t);
    }

    @Transactional
    public TransferResponse update(Long id, TransferRequest req) {
        StockTransfer t = draft(id);
        apply(t, req);
        return toResponse(t);
    }

    /** A draft is deleted, not cancelled: nothing has moved yet. */
    @Transactional
    public void delete(Long id) {
        transfers.delete(draft(id));
    }

    /** Same branch: out of one warehouse and into the other in one transaction. */
    @Transactional
    public TransferResponse move(Long id) {
        StockTransfer t = locked(id);
        requireStatus(t, StockTransfer.Status.DRAFT);
        BusinessContext.requireBranch(branchOf(t.getFromWarehouseId()));
        if (t.getKind() != StockTransfer.Kind.MOVE) {
            throw ApiException.rule("WRONG_STATUS", t.getKind());
        }
        StockService.Ref ref = new StockService.Ref(REF, t.getId());
        List<StockTransferItem> sent = takeOut(t, ref);
        for (StockTransferItem item : sent) {
            stock.receive(products.active(item.getProductId()), t.getToWarehouseId(), item.getBaseQty(),
                    item.getUnitCost(), item.getBatchNo(), item.getExpiryDate(), StockMovement.Type.TRANSFER_IN, ref);
            item.setQtyReceived(item.getBaseQty());
        }
        Instant now = Instant.now();
        t.setStatus(StockTransfer.Status.MOVED);
        t.setSentAt(now);
        t.setSentBy(CurrentUser.id());
        t.setReceivedAt(now);
        t.setReceivedBy(CurrentUser.id());
        return toResponse(t);
    }

    /** Other branch: stock leaves the source now and is in transit until received or returned. */
    @Transactional
    public TransferResponse send(Long id) {
        StockTransfer t = locked(id);
        requireStatus(t, StockTransfer.Status.DRAFT);
        if (t.getKind() != StockTransfer.Kind.BRANCH) {
            throw ApiException.rule("WRONG_STATUS", t.getKind());
        }
        BusinessContext.requireBranch(branchOf(t.getFromWarehouseId()));
        takeOut(t, new StockService.Ref(REF, t.getId()));
        t.setStatus(StockTransfer.Status.IN_TRANSIT);
        t.setSentAt(Instant.now());
        t.setSentBy(CurrentUser.id());
        return toResponse(t);
    }

    /**
     * The full quantity sent comes in at the destination; a shortfall then leaves through an automatic
     * "lost in transit" adjustment at the cost on the line.
     */
    @Transactional
    public TransferResponse receive(Long id, TransferReceiveRequest req) {
        StockTransfer t = locked(id);
        requireStatus(t, StockTransfer.Status.IN_TRANSIT);
        Warehouse destination = warehouses.get(t.getToWarehouseId());
        BusinessContext.requireBranch(destination.getBranchId());
        if (req != null && req.toWarehouseId() != null && !req.toWarehouseId().equals(destination.getId())) {
            Warehouse other = warehouses.get(req.toWarehouseId());
            if (!other.getBranchId().equals(destination.getBranchId()) || !other.isActive()) {
                throw ApiException.rule("WAREHOUSE_NOT_IN_BRANCH");
            }
            destination = other;
            t.setToWarehouseId(other.getId());
        }
        Map<Long, BigDecimal> received = new HashMap<>();
        if (req != null && req.lines() != null) {
            req.lines().forEach(l -> received.put(l.itemId(), l.qtyReceived()));
        }

        StockService.Ref ref = new StockService.Ref(REF, t.getId());
        List<AdjustmentService.Loss> losses = new ArrayList<>();
        for (StockTransferItem item : sortedItems(t)) {
            BigDecimal arrived = received.getOrDefault(item.getId(), item.getBaseQty());
            if (arrived.compareTo(item.getBaseQty()) > 0) {
                throw ApiException.rule("RECEIVED_MORE_THAN_SENT");
            }
            Product p = products.active(item.getProductId());
            StockBatch batch = stock.receive(p, destination.getId(), item.getBaseQty(), item.getUnitCost(),
                    item.getBatchNo(), item.getExpiryDate(), StockMovement.Type.TRANSFER_IN, ref);
            item.setQtyReceived(arrived);
            BigDecimal shortfall = item.getBaseQty().subtract(arrived);
            if (shortfall.signum() > 0) {
                losses.add(new AdjustmentService.Loss(p, batch.getId(), shortfall, item.getUnitCost()));
            }
        }
        if (!losses.isEmpty()) {
            t.setAdjustmentId(adjustments.postLostInTransit(destination, losses, t.getNumber()).getId());
        }
        t.setStatus(StockTransfer.Status.RECEIVED);
        t.setReceivedAt(Instant.now());
        t.setReceivedBy(CurrentUser.id());
        return toResponse(t);
    }

    /** Instead of receiving: the same batches go back into the source warehouse at the same cost. */
    @Transactional
    public TransferResponse returnToSender(Long id, ReturnRequest req) {
        StockTransfer t = locked(id);
        requireStatus(t, StockTransfer.Status.IN_TRANSIT);
        BusinessContext.requireBranch(branchOf(t.getFromWarehouseId()));
        StockService.Ref ref = new StockService.Ref(REF, t.getId());
        for (StockTransferItem item : sortedItems(t)) {
            stock.receive(products.active(item.getProductId()), t.getFromWarehouseId(), item.getBaseQty(),
                    item.getUnitCost(), item.getBatchNo(), item.getExpiryDate(), StockMovement.Type.TRANSFER_RETURN, ref);
        }
        t.setStatus(StockTransfer.Status.RETURNED);
        t.setReturnedAt(Instant.now());
        t.setReturnedBy(CurrentUser.id());
        t.setReturnReason(req.reason());
        return toResponse(t);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> list(StockTransfer.Status status, Pageable pageable) {
        return PageResponse.of(transfers.search(scope.warehouseIds(), status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public TransferResponse get(Long id) {
        StockTransfer t = transfers.get(id);
        if (!BusinessContext.canAccessBranch(branchOf(t.getFromWarehouseId()))
                && !BusinessContext.canAccessBranch(branchOf(t.getToWarehouseId()))) {
            throw ApiException.notFound("Transfer");
        }
        return toResponse(t);
    }

    /**
     * Takes every line out of the source (TRANSFER_OUT), earliest expiry first, and splits the lines per
     * batch so each keeps its batch, expiry and cost.
     */
    private List<StockTransferItem> takeOut(StockTransfer t, StockService.Ref ref) {
        List<StockTransferItem> split = new ArrayList<>();
        for (StockTransferItem line : sortedItems(t)) {
            Product p = products.active(line.getProductId());
            List<StockService.Pick> picks = line.getBatchId() != null
                    ? List.of(stock.issueFromBatch(p, line.getBatchId(), line.getBaseQty(), StockMovement.Type.TRANSFER_OUT, ref))
                    : stock.issue(p, t.getFromWarehouseId(), line.getBaseQty(), StockMovement.Type.TRANSFER_OUT, ref, false);
            for (StockService.Pick pick : picks) {
                StockTransferItem item = new StockTransferItem();
                item.setProductId(line.getProductId());
                boolean whole = picks.size() == 1;
                item.setUnitId(whole ? line.getUnitId() : p.getBaseUnitId());
                item.setQty(whole ? line.getQty() : pick.qty());
                item.setBaseQty(pick.qty());
                item.setBatchId(pick.batchId());
                item.setBatchNo(pick.batchNo());
                item.setExpiryDate(pick.expiryDate());
                item.setUnitCost(pick.unitCost());
                split.add(item);
            }
        }
        t.getItems().clear();
        t.getItems().addAll(split);
        return split;
    }

    private void apply(StockTransfer t, TransferRequest req) {
        Warehouse from = warehouses.get(req.fromWarehouseId());
        BusinessContext.requireBranch(from.getBranchId());
        Warehouse to;
        if (req.toWarehouseId() != null) {
            to = warehouses.get(req.toWarehouseId());
        } else if (req.toBranchId() != null) {
            to = branches.receivingWarehouse(req.toBranchId());
        } else {
            throw ApiException.badRequest("VALIDATION_FAILED");
        }
        if (from.getId().equals(to.getId())) {
            throw ApiException.badRequest("SAME_WAREHOUSE");
        }
        if (!from.isActive() || !to.isActive()) {
            throw ApiException.rule("WAREHOUSE_IN_USE");
        }
        t.setFromWarehouseId(from.getId());
        t.setToWarehouseId(to.getId());
        t.setKind(from.getBranchId().equals(to.getBranchId()) ? StockTransfer.Kind.MOVE : StockTransfer.Kind.BRANCH);
        t.setCarrierNote(req.carrierNote());
        t.setNote(req.note());
        t.getItems().clear();
        for (TransferLineRequest line : req.lines()) {
            Product p = products.active(line.productId());
            if (line.batchId() != null) {
                StockBatch b = batches.get(line.batchId());
                if (!b.getProductId().equals(p.getId()) || !b.getWarehouseId().equals(from.getId())) {
                    throw ApiException.notFound("Batch");
                }
            }
            StockTransferItem item = new StockTransferItem();
            item.setProductId(p.getId());
            item.setUnitId(line.unitId() == null ? p.getBaseUnitId() : line.unitId());
            item.setQty(line.qty());
            item.setBaseQty(products.toBaseQty(p, line.unitId(), line.qty()));
            item.setBatchId(line.batchId());
            t.getItems().add(item);
        }
    }

    private StockTransfer draft(Long id) {
        StockTransfer t = transfers.get(id);
        BusinessContext.requireBranch(branchOf(t.getFromWarehouseId()));
        if (t.getStatus() != StockTransfer.Status.DRAFT) {
            throw ApiException.rule("DOCUMENT_NOT_EDITABLE");
        }
        return t;
    }

    private StockTransfer locked(Long id) {
        transfers.get(id);
        return transfers.lockById(id).orElseThrow();
    }

    private static void requireStatus(StockTransfer t, StockTransfer.Status status) {
        if (t.getStatus() != status) {
            throw ApiException.rule("WRONG_STATUS", t.getStatus());
        }
    }

    private static List<StockTransferItem> sortedItems(StockTransfer t) {
        return t.getItems().stream().sorted(Comparator.comparing(StockTransferItem::getProductId)).toList();
    }

    private Long branchOf(Long warehouseId) {
        return warehouses.get(warehouseId).getBranchId();
    }

    private TransferResponse toResponse(StockTransfer t) {
        Map<Long, String> labels = branches.warehouseLabels();
        Map<Long, String> symbols = products.unitSymbols();
        Map<Long, Product> byId = products.byIds(t.getItems().stream().map(StockTransferItem::getProductId).toList());
        List<TransferLineResponse> lines = t.getItems().stream().map(i -> new TransferLineResponse(i.getId(),
                i.getProductId(), byId.get(i.getProductId()).getNameEn(), i.getUnitId(), symbols.get(i.getUnitId()),
                i.getQty(), i.getBaseQty(), i.getBatchId(), i.getBatchNo(), i.getExpiryDate(), i.getUnitCost(),
                i.getQtyReceived())).toList();
        int shortLines = (int) t.getItems().stream()
                .filter(i -> i.getQtyReceived() != null && i.getQtyReceived().compareTo(i.getBaseQty()) < 0).count();
        return new TransferResponse(t.getId(), t.getNumber(), t.getKind(), t.getStatus(),
                t.getFromWarehouseId(), labels.get(t.getFromWarehouseId()), branchOf(t.getFromWarehouseId()),
                t.getToWarehouseId(), labels.get(t.getToWarehouseId()), branchOf(t.getToWarehouseId()),
                t.getSentAt(), t.getReceivedAt(), t.getReturnedAt(), t.getReturnReason(), t.getCarrierNote(),
                t.getNote(), shortLines, t.getAdjustmentId(), t.value().setScale(2, java.math.RoundingMode.HALF_UP),
                lines);
    }
}
