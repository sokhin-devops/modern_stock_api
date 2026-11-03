package com.modernstock.business;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.billing.EntitlementService;
import com.modernstock.common.ApiException;
import com.modernstock.common.Phones;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Branches and their warehouses. Every branch keeps at least one warehouse, with exactly one sales
 * and one receiving warehouse (they can be the same). A new branch gets a hidden "Main" warehouse.
 */
@Service
@RequiredArgsConstructor
public class BranchService {

    public record BranchRequest(
            @Schema(description = "Short code; generated (B01, B02...) when empty") @Size(max = 20) String code,
            @NotBlank @Size(max = 120) String nameEn,
            @Size(max = 120) String nameKm,
            @Size(max = 255) String address,
            @Pattern(regexp = Phones.KH, message = "{phone.invalid}") String phone,
            @Size(max = 60) String province) {
    }

    public record WarehouseRequest(@NotBlank @Size(max = 120) String nameEn, @Size(max = 120) String nameKm,
                                   boolean sales, boolean receiving, Boolean active) {
    }

    public record WarehouseResponse(Long id, Long branchId, String nameEn, String nameKm, boolean sales,
                                    boolean receiving, boolean active) {
        static WarehouseResponse from(Warehouse w) {
            return new WarehouseResponse(w.getId(), w.getBranchId(), w.getNameEn(), w.getNameKm(), w.isSales(),
                    w.isReceiving(), w.isActive());
        }
    }

    public record BranchResponse(Long id, String code, String nameEn, String nameKm, String address, String phone,
                                 String province, boolean main, boolean active, List<WarehouseResponse> warehouses) {
    }

    private final BranchRepository branches;
    private final WarehouseRepository warehouses;
    private final EntitlementService entitlements;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<BranchResponse> list() {
        return branches.findAllByOrderByMainDescNameEnAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BranchResponse get(Long id) {
        return toResponse(branches.get(id));
    }

    @Transactional
    public BranchResponse create(BranchRequest req) {
        entitlements.checkCanAdd(EntitlementService.BRANCHES);
        return toResponse(createInternal(req, false));
    }

    /** Also used by the setup page for the main branch. */
    @Transactional
    public Branch createInternal(BranchRequest req, boolean main) {
        Branch b = new Branch();
        b.setCode(req.code() == null || req.code().isBlank() ? nextCode() : req.code().trim().toUpperCase());
        if (branches.existsByCodeIgnoreCase(b.getCode())) {
            throw ApiException.conflict("BRANCH_CODE_TAKEN", b.getCode());
        }
        apply(b, req);
        b.setMain(main);
        branches.save(b);

        Warehouse w = new Warehouse();
        w.setBranchId(b.getId());
        w.setNameEn("Main");
        w.setNameKm("ឃ្លាំងមេ");
        w.setSales(true);
        w.setReceiving(true);
        warehouses.save(w);
        return b;
    }

    @Transactional
    public BranchResponse update(Long id, BranchRequest req) {
        Branch b = branches.get(id);
        apply(b, req);
        if (req.code() != null && !req.code().isBlank() && !req.code().equalsIgnoreCase(b.getCode())) {
            if (branches.existsByCodeIgnoreCase(req.code())) {
                throw ApiException.conflict("BRANCH_CODE_TAKEN", req.code());
            }
            b.setCode(req.code().trim().toUpperCase());
        }
        return toResponse(b);
    }

    /** Branches are never deleted (documents point to them). Blocked while stock is in transit (PLAIN.md 11.9). */
    @Transactional
    public BranchResponse deactivate(Long id) {
        Branch b = branches.get(id);
        if (b.isMain()) {
            throw ApiException.rule("MAIN_BRANCH_REQUIRED");
        }
        Long inTransit = jdbc.queryForObject("""
                SELECT count(*) FROM stock_transfers t
                WHERE t.business_id = ? AND t.status = 'IN_TRANSIT'
                  AND EXISTS (SELECT 1 FROM warehouses w
                              WHERE w.id IN (t.from_warehouse_id, t.to_warehouse_id) AND w.branch_id = ?)
                """, Long.class, b.getBusinessId(), b.getId());
        if (inTransit != null && inTransit > 0) {
            throw ApiException.rule("BRANCH_HAS_TRANSFERS_IN_TRANSIT", inTransit);
        }
        b.setActive(false);
        return toResponse(b);
    }

    @Transactional
    public WarehouseResponse addWarehouse(Long branchId, WarehouseRequest req) {
        branches.get(branchId);
        Warehouse w = new Warehouse();
        w.setBranchId(branchId);
        w.setNameEn(req.nameEn());
        w.setNameKm(req.nameKm());
        warehouses.save(w);
        applyRoles(w, req);
        return WarehouseResponse.from(w);
    }

    @Transactional
    public WarehouseResponse updateWarehouse(Long branchId, Long id, WarehouseRequest req) {
        Warehouse w = warehouses.get(id);
        if (!w.getBranchId().equals(branchId)) {
            throw ApiException.notFound("Warehouse");
        }
        if ((w.isSales() && !req.sales()) || (w.isReceiving() && !req.receiving())) {
            throw ApiException.rule("WAREHOUSE_ROLE_REQUIRED");
        }
        w.setNameEn(req.nameEn());
        w.setNameKm(req.nameKm());
        if (Boolean.FALSE.equals(req.active()) && w.isActive()) {
            BigDecimal stock = jdbc.queryForObject(
                    "SELECT COALESCE(sum(qty_on_hand), 0) FROM stock_levels WHERE warehouse_id = ?",
                    BigDecimal.class, w.getId());
            if (req.sales() || req.receiving() || stock.signum() != 0) {
                throw ApiException.rule("WAREHOUSE_IN_USE");
            }
            w.setActive(false);
        } else if (Boolean.TRUE.equals(req.active())) {
            w.setActive(true);
        }
        applyRoles(w, req);
        return WarehouseResponse.from(w);
    }

    @Transactional(readOnly = true)
    public Warehouse salesWarehouse(Long branchId) {
        return warehouses.findFirstByBranchIdAndSalesTrueAndActiveTrue(branchId)
                .orElseThrow(() -> ApiException.rule("NO_SALES_WAREHOUSE"));
    }

    @Transactional(readOnly = true)
    public Warehouse receivingWarehouse(Long branchId) {
        return warehouses.findFirstByBranchIdAndReceivingTrueAndActiveTrue(branchId)
                .orElseThrow(() -> ApiException.rule("NO_RECEIVING_WAREHOUSE"));
    }

    /**
     * How a warehouse is shown: a branch with one warehouse shows only its name ("Sen Sok"),
     * with two or more "Toul Kork · Back store" (PLAIN.md 11.9).
     */
    @Transactional(readOnly = true)
    public Map<Long, String> warehouseLabels() {
        Map<Long, Branch> branchById = branches.findAll().stream().collect(Collectors.toMap(Branch::getId, b -> b));
        List<Warehouse> all = warehouses.findAll();
        Map<Long, Long> activeCount = all.stream().filter(Warehouse::isActive)
                .collect(Collectors.groupingBy(Warehouse::getBranchId, Collectors.counting()));
        Map<Long, String> labels = new HashMap<>();
        for (Warehouse w : all) {
            Branch b = branchById.get(w.getBranchId());
            String branchName = b == null ? "" : b.getNameEn();
            labels.put(w.getId(), activeCount.getOrDefault(w.getBranchId(), 0L) > 1
                    ? branchName + " · " + w.getNameEn() : branchName);
        }
        return labels;
    }

    private void applyRoles(Warehouse w, WarehouseRequest req) {
        if (req.sales() || req.receiving()) {
            for (Warehouse other : warehouses.findByBranchIdOrderByIdAsc(w.getBranchId())) {
                if (!other.getId().equals(w.getId())) {
                    if (req.sales()) {
                        other.setSales(false);
                    }
                    if (req.receiving()) {
                        other.setReceiving(false);
                    }
                }
            }
        }
        w.setSales(req.sales());
        w.setReceiving(req.receiving());
    }

    private void apply(Branch b, BranchRequest req) {
        b.setNameEn(req.nameEn());
        b.setNameKm(req.nameKm());
        b.setAddress(req.address());
        b.setPhone(req.phone());
        b.setProvince(req.province());
    }

    private String nextCode() {
        long n = branches.count() + 1;
        String code;
        do {
            code = "B%02d".formatted(n++);
        } while (branches.existsByCodeIgnoreCase(code));
        return code;
    }

    private BranchResponse toResponse(Branch b) {
        return new BranchResponse(b.getId(), b.getCode(), b.getNameEn(), b.getNameKm(), b.getAddress(), b.getPhone(),
                b.getProvince(), b.isMain(), b.isActive(),
                warehouses.findByBranchIdOrderByIdAsc(b.getId()).stream().map(WarehouseResponse::from).toList());
    }
}
