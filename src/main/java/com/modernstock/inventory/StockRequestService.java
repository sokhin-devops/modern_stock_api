package com.modernstock.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.auth.User;
import com.modernstock.auth.UserRepository;
import com.modernstock.business.Branch;
import com.modernstock.business.BranchRepository;
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
import com.modernstock.inventory.TransferService.TransferLineRequest;
import com.modernstock.inventory.TransferService.TransferResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Transfer requests (FR-4.11): a branch asks another for goods; whoever manages transfers in that branch makes the
 * transfer from it (a draft from its sales warehouse, to check and send), or declines with a reason. The branch that
 * asked can cancel while it is open. Both branches see the request; members see it when they work in either.
 */
@Service
@RequiredArgsConstructor
public class StockRequestService {

    public record StockRequestLine(@NotNull Long productId,
                                   @Schema(description = "Empty = base unit") Long unitId,
                                   @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty) {
    }

    public record StockRequestCreate(@Schema(description = "The branch asked for the goods") @NotNull Long fromBranchId,
                                     @Schema(description = "The branch that asks; defaults to the selected branch") Long toBranchId,
                                     LocalDate neededBy,
                                     @Size(max = 500) String note,
                                     @NotEmpty List<@Valid StockRequestLine> lines) {
    }

    public record DeclineRequest(@Schema(example = "Out of stock here too") @NotBlank @Size(max = 255) String reason) {
    }

    public record FulfilRequest(@Schema(description = "A warehouse of the branch asked; empty = its sales warehouse") Long fromWarehouseId) {
    }

    public record StockRequestLineResponse(Long productId, String productName, String productNameKm, Long unitId, String unitSymbol,
                                           BigDecimal qty, BigDecimal baseQty,
                                           @Schema(description = "On hand in the branch asked, base units") BigDecimal available) {
    }

    public record StockRequestResponse(Long id, String number, StockRequest.Status status,
                                       Long fromBranchId, String fromBranchName, Long toBranchId, String toBranchName,
                                       LocalDate neededBy, String note, Instant createdAt, String requestedByName,
                                       Instant decidedAt, String decidedByName, String declineReason,
                                       Long transferId, String transferNumber,
                                       @Schema(description = "This member may make the transfer or decline") boolean canFulfil,
                                       @Schema(description = "This member may cancel it") boolean canCancel,
                                       List<StockRequestLineResponse> lines) {
    }

    private final StockRequestRepository requests;
    private final StockTransferRepository transfers;
    private final TransferService transferService;
    private final BranchRepository branchRepository;
    private final BranchService branches;
    private final WarehouseRepository warehouses;
    private final ProductService products;
    private final DocumentNumberService numbers;
    private final BranchScope scope;
    private final UserRepository users;

    @Transactional
    public StockRequestResponse create(StockRequestCreate req) {
        Long to = BusinessContext.branchForWrite(req.toBranchId());
        Branch from = branchRepository.get(req.fromBranchId());
        if (from.getId().equals(to)) {
            throw ApiException.badRequest("REQUEST_SAME_BRANCH");
        }
        if (!from.isActive()) {
            throw ApiException.rule("BRANCH_INACTIVE");
        }
        StockRequest r = new StockRequest();
        r.setNumber(numbers.next(DocType.TRQ));
        r.setFromBranchId(from.getId());
        r.setToBranchId(to);
        r.setStatus(StockRequest.Status.OPEN);
        r.setNeededBy(req.neededBy());
        r.setNote(req.note() == null || req.note().isBlank() ? null : req.note().strip());
        r.setRequestedBy(CurrentUser.id());
        for (StockRequestLine line : req.lines()) {
            Product p = products.active(line.productId());
            StockRequestItem item = new StockRequestItem();
            item.setProductId(p.getId());
            item.setUnitId(line.unitId() == null ? p.getBaseUnitId() : line.unitId());
            item.setQty(line.qty());
            item.setBaseQty(products.toBaseQty(p, line.unitId(), line.qty()));
            r.getItems().add(item);
        }
        requests.save(r);
        return toResponse(r);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockRequestResponse> list(StockRequest.Status status, Pageable pageable) {
        return PageResponse.of(requests.search(scope.branchIds(), status, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public StockRequestResponse get(Long id) {
        return toResponse(visible(id));
    }

    /** The branch asked makes the transfer: a draft with the lines asked for, to check (batches, amounts) and send. */
    @Transactional
    public StockRequestResponse fulfil(Long id, FulfilRequest req) {
        StockRequest r = open(id);
        BusinessContext.requireBranch(r.getFromBranchId());
        Warehouse from = req == null || req.fromWarehouseId() == null ? branches.salesWarehouse(r.getFromBranchId())
                : warehouses.get(req.fromWarehouseId());
        if (!from.getBranchId().equals(r.getFromBranchId())) {
            throw ApiException.notFound("Warehouse");
        }
        TransferResponse t = transferService.create(new TransferService.TransferRequest(from.getId(), null, r.getToBranchId(), null,
                r.getNumber() + (r.getNote() == null ? "" : " · " + r.getNote()),
                r.getItems().stream().map(i -> new TransferLineRequest(i.getProductId(), i.getUnitId(), i.getQty(), null)).toList()));
        r.setTransferId(t.id());
        decide(r, StockRequest.Status.FULFILLED);
        return toResponse(r);
    }

    @Transactional
    public StockRequestResponse decline(Long id, DeclineRequest req) {
        StockRequest r = open(id);
        BusinessContext.requireBranch(r.getFromBranchId());
        r.setDeclineReason(req.reason().strip());
        decide(r, StockRequest.Status.DECLINED);
        return toResponse(r);
    }

    @Transactional
    public StockRequestResponse cancel(Long id) {
        StockRequest r = open(id);
        BusinessContext.requireBranch(r.getToBranchId());
        decide(r, StockRequest.Status.CANCELLED);
        return toResponse(r);
    }

    // ------------------------------------------------------------------ helpers

    private StockRequest visible(Long id) {
        StockRequest r = requests.get(id);
        if (!BusinessContext.canAccessBranch(r.getFromBranchId()) && !BusinessContext.canAccessBranch(r.getToBranchId())) {
            throw ApiException.notFound("Transfer request");
        }
        return r;
    }

    private StockRequest open(Long id) {
        StockRequest r = visible(id);
        if (r.getStatus() != StockRequest.Status.OPEN) {
            throw ApiException.conflict("WRONG_STATUS", r.getStatus());
        }
        return r;
    }

    private static void decide(StockRequest r, StockRequest.Status status) {
        r.setStatus(status);
        r.setDecidedBy(CurrentUser.id());
        r.setDecidedAt(Instant.now());
    }

    private StockRequestResponse toResponse(StockRequest r) {
        Map<Long, Product> byId = products.byIds(r.getItems().stream().map(StockRequestItem::getProductId).toList());
        Map<Long, String> symbols = products.unitSymbols();
        List<Long> sourceWarehouses = warehouses.findByBranchIdAndActiveTrue(r.getFromBranchId()).stream().map(Warehouse::getId).toList();
        Map<Long, BigDecimal> onHand = products.onHand(byId.keySet(), sourceWarehouses);
        boolean manage = BusinessContext.has("transfer:manage") && r.getStatus() == StockRequest.Status.OPEN;
        return new StockRequestResponse(r.getId(), r.getNumber(), r.getStatus(),
                r.getFromBranchId(), branchName(r.getFromBranchId()), r.getToBranchId(), branchName(r.getToBranchId()),
                r.getNeededBy(), r.getNote(), r.getCreatedAt(), userName(r.getRequestedBy()), r.getDecidedAt(), userName(r.getDecidedBy()),
                r.getDeclineReason(), r.getTransferId(),
                r.getTransferId() == null ? null : transfers.findById(r.getTransferId()).map(StockTransfer::getNumber).orElse(null),
                manage && BusinessContext.canAccessBranch(r.getFromBranchId()),
                manage && BusinessContext.canAccessBranch(r.getToBranchId()),
                r.getItems().stream().map(i -> {
                    Product p = byId.get(i.getProductId());
                    return new StockRequestLineResponse(i.getProductId(), p == null ? null : p.getNameEn(), p == null ? null : p.getNameKm(),
                            i.getUnitId(), symbols.get(i.getUnitId()), i.getQty(), i.getBaseQty(),
                            onHand.getOrDefault(i.getProductId(), BigDecimal.ZERO));
                }).toList());
    }

    private String branchName(Long id) {
        return branchRepository.findById(id).map(Branch::getNameEn).orElse(null);
    }

    private String userName(Long id) {
        return id == null ? null : users.findById(id).map(User::getFullName).orElse(null);
    }
}
