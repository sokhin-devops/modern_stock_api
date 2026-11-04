package com.modernstock.catalog;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.catalog.ImportService.ImportResult;
import com.modernstock.catalog.ImportService.ProductRow;
import com.modernstock.catalog.ImportService.StockRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Excel import (FR-1.8): check the rows first (dryRun=true), then import the rows without problems. */
@Tag(name = "Import")
@RestController
@RequestMapping("/import")
@RequiredArgsConstructor
public class ImportController {

    public record ProductImport(@NotEmpty @Size(max = 2000) List<ProductRow> rows) {
    }

    public record StockImport(@NotNull Long warehouseId, @NotEmpty @Size(max = 2000) List<StockRow> rows) {
    }

    private final ImportService imports;

    @Operation(summary = "Import products",
            description = "Each row: its problems (errors) and what will happen (notes, e.g. a new category). dryRun=false "
                    + "imports the rows without errors; missing categories are made.")
    @PostMapping("/products")
    @PreAuthorize("hasAuthority('product:manage')")
    public ImportResult importProducts(@RequestParam(defaultValue = "true") boolean dryRun, @Valid @RequestBody ProductImport req) {
        return imports.products(req.rows(), dryRun);
    }

    @Operation(summary = "Import opening stock",
            description = "Rows by SKU or barcode, with quantity, unit cost, batch and expiry. dryRun=false posts one opening "
                    + "stock document with the rows without errors.")
    @PostMapping("/opening-stock")
    @PreAuthorize("hasAuthority('stock:opening')")
    public ImportResult importOpeningStock(@RequestParam(defaultValue = "true") boolean dryRun, @Valid @RequestBody StockImport req) {
        return imports.openingStock(req.warehouseId(), req.rows(), dryRun);
    }
}
