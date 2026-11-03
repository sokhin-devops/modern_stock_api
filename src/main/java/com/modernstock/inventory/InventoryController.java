package com.modernstock.inventory;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.common.PageResponse;
import com.modernstock.inventory.AdjustmentService.AdjustmentRequest;
import com.modernstock.inventory.AdjustmentService.AdjustmentResponse;
import com.modernstock.inventory.TransferService.ReceiveRequest;
import com.modernstock.inventory.TransferService.ReturnRequest;
import com.modernstock.inventory.TransferService.TransferRequest;
import com.modernstock.inventory.TransferService.TransferResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Adjustments and transfers: the documents that change stock inside the business. */
@RestController
@RequiredArgsConstructor
public class InventoryController {

    private final AdjustmentService adjustments;
    private final TransferService transfers;

    // ------------------------------------------------------------------ adjustments

    @Tag(name = "Adjustments")
    @GetMapping("/adjustments")
    @PreAuthorize("hasAuthority('stock:view')")
    public PageResponse<AdjustmentResponse> adjustments(@RequestParam(required = false) StockAdjustment.Status status,
                                                        @ParameterObject Pageable pageable) {
        return adjustments.list(status, pageable);
    }

    @Tag(name = "Adjustments")
    @GetMapping("/adjustments/{id}")
    @PreAuthorize("hasAuthority('stock:view')")
    public AdjustmentResponse adjustment(@PathVariable Long id) {
        return adjustments.get(id);
    }

    @Tag(name = "Adjustments")
    @Operation(summary = "Opening stock or an adjustment",
            description = "kind OPENING needs stock:opening and a unitCost per line (it updates the average cost). "
                    + "kind ADJUSTMENT is posted at once with adjustment:post; with only adjustment:request it waits for approval. "
                    + "Removing stock takes the earliest expiry first, expired batches included.")
    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('stock:opening', 'adjustment:post', 'adjustment:request')")
    public AdjustmentResponse createAdjustment(@Valid @RequestBody AdjustmentRequest req) {
        return adjustments.create(req);
    }

    @Tag(name = "Adjustments")
    @PostMapping("/adjustments/{id}/approve")
    @PreAuthorize("hasAuthority('adjustment:approve')")
    public AdjustmentResponse approve(@PathVariable Long id) {
        return adjustments.approve(id);
    }

    @Tag(name = "Adjustments")
    @PostMapping("/adjustments/{id}/reject")
    @PreAuthorize("hasAuthority('adjustment:approve')")
    public AdjustmentResponse reject(@PathVariable Long id) {
        return adjustments.reject(id);
    }

    // ------------------------------------------------------------------ transfers

    @Tag(name = "Transfers")
    @Operation(summary = "Transfers of the branches you see", description = "A transfer shows for both the sending and receiving branch.")
    @GetMapping("/transfers")
    @PreAuthorize("hasAuthority('stock:view')")
    public PageResponse<TransferResponse> transfers(@RequestParam(required = false) StockTransfer.Status status,
                                                    @ParameterObject Pageable pageable) {
        return transfers.list(status, pageable);
    }

    @Tag(name = "Transfers")
    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasAuthority('stock:view')")
    public TransferResponse transfer(@PathVariable Long id) {
        return transfers.get(id);
    }

    @Tag(name = "Transfers")
    @Operation(summary = "Create a draft",
            description = "Same branch = kind MOVE, other branch = kind BRANCH. A draft changes nothing and can be edited or deleted.")
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('transfer:manage')")
    public TransferResponse createTransfer(@Valid @RequestBody TransferRequest req) {
        return transfers.create(req);
    }

    @Tag(name = "Transfers")
    @PutMapping("/transfers/{id}")
    @PreAuthorize("hasAuthority('transfer:manage')")
    public TransferResponse updateTransfer(@PathVariable Long id, @Valid @RequestBody TransferRequest req) {
        return transfers.update(id, req);
    }

    @Tag(name = "Transfers")
    @DeleteMapping("/transfers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('transfer:manage')")
    public void deleteTransfer(@PathVariable Long id) {
        transfers.delete(id);
    }

    @Tag(name = "Transfers")
    @Operation(summary = "Move (same branch)", description = "Out of one warehouse and into the other in one transaction. Status MOVED.")
    @PostMapping("/transfers/{id}/move")
    @PreAuthorize("hasAuthority('transfer:manage')")
    public TransferResponse move(@PathVariable Long id) {
        return transfers.move(id);
    }

    @Tag(name = "Transfers")
    @Operation(summary = "Send (other branch)",
            description = "Stock leaves the source, earliest expiry first; lines are split per batch. Status IN_TRANSIT.")
    @PostMapping("/transfers/{id}/send")
    @PreAuthorize("hasAuthority('transfer:manage')")
    public TransferResponse send(@PathVariable Long id) {
        return transfers.send(id);
    }

    @Tag(name = "Transfers")
    @Operation(summary = "Receive at the destination",
            description = "Lines default to what was sent and can't be more. A shortfall becomes a posted \"lost in transit\" adjustment.")
    @PostMapping("/transfers/{id}/receive")
    @PreAuthorize("hasAuthority('transfer:manage')")
    public TransferResponse receive(@PathVariable Long id, @Valid @RequestBody(required = false) ReceiveRequest req) {
        return transfers.receive(id, req);
    }

    @Tag(name = "Transfers")
    @Operation(summary = "Return to sender", description = "Instead of receiving: the same batches go back into the source at the same cost.")
    @PostMapping("/transfers/{id}/return")
    @PreAuthorize("hasAuthority('transfer:return_void')")
    public TransferResponse returnToSender(@PathVariable Long id, @Valid @RequestBody ReturnRequest req) {
        return transfers.returnToSender(id, req);
    }
}
