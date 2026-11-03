package com.modernstock.catalog;

import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import com.modernstock.catalog.ProductService.BarcodeLookupResponse;
import com.modernstock.catalog.ProductService.BranchPriceRequest;
import com.modernstock.catalog.ProductService.BranchPriceResponse;
import com.modernstock.catalog.ProductService.ProductRequest;
import com.modernstock.catalog.ProductService.ProductResponse;
import com.modernstock.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Products")
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService products;

    @Operation(summary = "Search products", description = "q matches name (EN/KM), SKU or barcode. Sort by nameEn, sku, sellPrice...")
    @GetMapping
    @PreAuthorize("hasAuthority('product:view')")
    public PageResponse<ProductResponse> search(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) Long categoryId,
                                                @Parameter(description = "Default true; false lists deactivated products")
                                                @RequestParam(required = false) Boolean active,
                                                @ParameterObject @PageableDefault(size = 20, sort = "nameEn") Pageable pageable) {
        return products.search(q, categoryId, active, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('product:view')")
    public ProductResponse get(@PathVariable Long id) {
        return products.get(id);
    }

    @Operation(summary = "Find a product by scanning", description = "Matches the product barcode or a box/pack barcode. "
            + "Returns the scanned unit, its price in the branch and the stock in the branch's sales warehouse.")
    @GetMapping("/barcode/{code}")
    @PreAuthorize("hasAuthority('product:view')")
    public BarcodeLookupResponse byBarcode(@PathVariable String code,
                                           @Parameter(description = "Defaults to the selected branch (X-Branch-Id)")
                                           @RequestParam(required = false) Long branchId) {
        return products.byBarcode(code, branchId);
    }

    @Operation(summary = "Add a product", description = "Checks the plan's product limit.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('product:manage')")
    public ProductResponse create(@Valid @RequestBody ProductRequest req) {
        return products.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('product:manage')")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        return products.update(id, req);
    }

    @Operation(summary = "Deactivate a product", description = "Products are never deleted, because documents point to them.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('product:manage')")
    public void deactivate(@PathVariable Long id) {
        products.deactivate(id);
    }

    @GetMapping("/{id}/branch-prices")
    @PreAuthorize("hasAuthority('product:view')")
    public List<BranchPriceResponse> branchPrices(@PathVariable Long id) {
        return products.branchPrices(id);
    }

    @Operation(summary = "Set prices for some branches", description = "A price overrides the default in one branch; sellPrice empty removes it.")
    @PutMapping("/{id}/branch-prices")
    @PreAuthorize("hasAuthority('branch_price:manage')")
    public List<BranchPriceResponse> setBranchPrices(@PathVariable Long id,
                                                     @Valid @RequestBody List<@Valid BranchPriceRequest> req) {
        return products.setBranchPrices(id, req);
    }
}
