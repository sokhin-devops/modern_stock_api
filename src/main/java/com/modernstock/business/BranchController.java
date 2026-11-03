package com.modernstock.business;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BranchService.BranchRequest;
import com.modernstock.business.BranchService.BranchResponse;
import com.modernstock.business.BranchService.WarehouseRequest;
import com.modernstock.business.BranchService.WarehouseResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Branches")
@RestController
@RequestMapping("/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchService branches;

    @Operation(summary = "All branches with their warehouses", description = "Main branch first.")
    @GetMapping
    public List<BranchResponse> list() {
        return branches.list();
    }

    @GetMapping("/{id}")
    public BranchResponse get(@PathVariable Long id) {
        return branches.get(id);
    }

    @Operation(summary = "Add a branch", description = "Checks the plan's branch limit. Creates a Main warehouse that sells and receives.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('business:manage')")
    public BranchResponse create(@Valid @RequestBody BranchRequest req) {
        return branches.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('business:manage')")
    public BranchResponse update(@PathVariable Long id, @Valid @RequestBody BranchRequest req) {
        return branches.update(id, req);
    }

    @Operation(summary = "Deactivate a branch",
            description = "Branches are never deleted. Not allowed for the main branch, or while transfers to or from it are in transit.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('business:manage')")
    public BranchResponse deactivate(@PathVariable Long id) {
        return branches.deactivate(id);
    }

    @Operation(summary = "Add a warehouse to a branch",
            description = "sales = the POS sells from it; receiving = goods arrive there. Setting one moves the role from the other warehouse.")
    @PostMapping("/{branchId}/warehouses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('business:manage')")
    public WarehouseResponse addWarehouse(@PathVariable Long branchId, @Valid @RequestBody WarehouseRequest req) {
        return branches.addWarehouse(branchId, req);
    }

    @Operation(summary = "Rename a warehouse, move its roles, or deactivate it",
            description = "A warehouse that sells, receives or still holds stock can't be deactivated.")
    @PutMapping("/{branchId}/warehouses/{id}")
    @PreAuthorize("hasAuthority('business:manage')")
    public WarehouseResponse updateWarehouse(@PathVariable Long branchId, @PathVariable Long id,
                                             @Valid @RequestBody WarehouseRequest req) {
        return branches.updateWarehouse(branchId, id, req);
    }
}
