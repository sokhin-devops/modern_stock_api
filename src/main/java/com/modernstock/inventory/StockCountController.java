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
                                                       @ParameterObject Pageable pageable) {
        return counts.list(status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('count:manage')")
    public CountResponse getStockCount(@PathVariable Long id) {
        return counts.get(id);
    }

    @Operation(summary = "Start a count", description = "Takes the system quantity of every active product in the warehouse (or category).")
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

    @Operation(summary = "Post a count", description = "Adds or removes each difference as a stock count movement.")
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('count:manage')")
    public CountResponse postStockCount(@PathVariable Long id) {
        return counts.post(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('count:manage')")
    public void deleteStockCount(@PathVariable Long id) {
        counts.delete(id);
    }
}
