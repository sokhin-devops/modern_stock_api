package com.modernstock.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@Tag(name = "Categories and units")
@RestController
@RequiredArgsConstructor
public class CatalogController {

    public record CategoryRequest(Long parentId, @NotBlank @Size(max = 120) String nameEn, @Size(max = 120) String nameKm) {
    }

    public record CategoryResponse(Long id, Long parentId, String nameEn, String nameKm) {
        static CategoryResponse from(Category c) {
            return new CategoryResponse(c.getId(), c.getParentId(), c.getNameEn(), c.getNameKm());
        }
    }

    public record UnitRequest(@NotBlank @Size(max = 60) String nameEn, @Size(max = 60) String nameKm,
                              @NotBlank @Size(max = 10) String symbol) {
    }

    public record UnitResponse(Long id, String nameEn, String nameKm, String symbol) {
        static UnitResponse from(Unit u) {
            return new UnitResponse(u.getId(), u.getNameEn(), u.getNameKm(), u.getSymbol());
        }
    }

    private final CategoryRepository categories;
    private final UnitRepository units;

    @Operation(summary = "All categories", description = "A tree: parentId points to the parent category.")
    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('product:view')")
    @Transactional(readOnly = true)
    public List<CategoryResponse> categories() {
        return categories.findAllByOrderByNameEnAsc().stream().map(CategoryResponse::from).toList();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('product:manage')")
    @Transactional
    public CategoryResponse createCategory(@Valid @RequestBody CategoryRequest req) {
        return CategoryResponse.from(categories.save(apply(new Category(), req)));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('product:manage')")
    @Transactional
    public CategoryResponse updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return CategoryResponse.from(apply(categories.get(id), req));
    }

    @Operation(summary = "Delete a category", description = "409 while products or sub-categories still use it.")
    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('product:manage')")
    @Transactional
    public void deleteCategory(@PathVariable Long id) {
        categories.delete(categories.get(id));
        categories.flush();
    }

    @GetMapping("/units")
    @PreAuthorize("hasAuthority('product:view')")
    @Transactional(readOnly = true)
    public List<UnitResponse> units() {
        return units.findAllByOrderByIdAsc().stream().map(UnitResponse::from).toList();
    }

    @PostMapping("/units")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('product:manage')")
    @Transactional
    public UnitResponse createUnit(@Valid @RequestBody UnitRequest req) {
        return UnitResponse.from(units.save(apply(new Unit(), req)));
    }

    @PutMapping("/units/{id}")
    @PreAuthorize("hasAuthority('product:manage')")
    @Transactional
    public UnitResponse updateUnit(@PathVariable Long id, @Valid @RequestBody UnitRequest req) {
        return UnitResponse.from(apply(units.get(id), req));
    }

    @Operation(summary = "Delete a unit", description = "409 while products use it.")
    @DeleteMapping("/units/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('product:manage')")
    @Transactional
    public void deleteUnit(@PathVariable Long id) {
        units.delete(units.get(id));
        units.flush();
    }

    private Category apply(Category c, CategoryRequest req) {
        if (req.parentId() != null) {
            categories.get(req.parentId());
        }
        c.setParentId(req.parentId());
        c.setNameEn(req.nameEn());
        c.setNameKm(req.nameKm());
        return c;
    }

    private static Unit apply(Unit u, UnitRequest req) {
        u.setNameEn(req.nameEn());
        u.setNameKm(req.nameKm());
        u.setSymbol(req.symbol());
        return u;
    }
}
