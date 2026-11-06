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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.common.PageResponse;
import com.modernstock.inventory.StockCountService.CountRequest;
import com.modernstock.inventory.StockCountService.CountResponse;
import com.modernstock.inventory.StockCountService.CountedRequest;
import com.modernstock.inventory.StockCountService.RecountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Stock counts")
@RestController
@RequestMapping("/stock-counts")
@RequiredArgsConstructor
public class StockCountController {

    private final StockCountService counts;

    @Operation(summary = "Stock counts", description = "Newest first, in the branches you see. Lines are left out; open one to see them.")
    @GetMapping
    @PreAuthorize("hasAuthority('count:manage')")
    public PageResponse<CountResponse> listStockCounts(@RequestParam(required = false) StockCount.Status status,
                                                       @RequestParam(required = false) String q,
                                                       @RequestParam(required = false) java.time.LocalDate from,
                                                       @RequestParam(required = false) java.time.LocalDate to,
                                                       @ParameterObject Pageable pageable) {
        return counts.list(status, q, from, to, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('count:manage')")
    public CountResponse getStockCount(@PathVariable Long id) {
        return counts.get(id);
    }

    @Operation(summary = "Start a count", description = "Takes the system quantity of each product in one warehouse, or every warehouse "
            + "of the branch: all active products, one category, or the products chosen (one shelf). blind hides the system quantity "
            + "from counters until the count is submitted.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('count:manage')")
    public CountResponse startStockCount(@Valid @RequestBody CountRequest req) {
        return counts.start(req);
    }

    @Operation(summary = "Save counted quantities", description = "Base units; empty = not counted (left unchanged when posting).")
    @PutMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('count:manage')")
    public CountResponse saveCountedQuantities(@PathVariable Long id, @Valid @RequestBody CountedRequest req) {
        return counts.saveCounted(id, req);
    }

    @Operation(summary = "Count moved products again", description = "Takes the system quantity again for lines whose stock moved "
            + "after the snapshot (or the lines given) and clears what was counted for them.")
    @PostMapping("/{id}/recount")
    @PreAuthorize("hasAuthority('count:manage')")
    public CountResponse recountStockCount(@PathVariable Long id, @RequestBody(required = false) RecountRequest req) {
        return counts.recount(id, req);
    }

    @Operation(summary = "Submit a count", description = "The counter is done; Owner or Manager posts it. 422 RECOUNT_NEEDED while a "
            + "counted product moved after its snapshot.")
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('count:manage')")
    public CountResponse submitStockCount(@PathVariable Long id) {
        return counts.submit(id);
    }

    @Operation(summary = "Send a count back", description = "A submitted count goes back to the counter.")
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('adjustment:approve')")
    public CountResponse reopenStockCount(@PathVariable Long id) {
        return counts.reopen(id);
    }

    @Operation(summary = "Post a count", description = "Owner or Manager: adds or removes each difference as a stock count movement. "
            + "A blind count is submitted first.")
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('adjustment:approve')")
    public CountResponse postStockCount(@PathVariable Long id) {
        return counts.post(id);
    }

    @Operation(summary = "Void a posted count", description = "Owner or Manager: every difference is reversed, back into the same "
            + "batches, while what it added is still there (422 VOID_STOCK_GONE).")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('adjustment:approve')")
    public CountResponse voidStockCount(@PathVariable Long id) {
        return counts.voidCount(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('count:manage')")
    public void deleteStockCount(@PathVariable Long id) {
        counts.delete(id);
    }
}
