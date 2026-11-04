package com.modernstock.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.modernstock.billing.EntitlementService;
import com.modernstock.business.BusinessContext;
import com.modernstock.catalog.ProductService.ProductRequest;
import com.modernstock.catalog.ProductService.ProductUnitRequest;
import com.modernstock.common.ApiException;
import com.modernstock.common.Messages;
import com.modernstock.inventory.AdjustmentService;
import com.modernstock.inventory.AdjustmentService.AdjustmentLineRequest;
import com.modernstock.inventory.AdjustmentService.AdjustmentRequest;
import com.modernstock.inventory.StockAdjustment;
import lombok.RequiredArgsConstructor;

/**
 * Excel import (FR-1.8): products with their category, unit, price, barcode and one packaging unit; and opening stock
 * of a warehouse with batch and expiry. Every row is checked first (dryRun) and its problems are shown row by row;
 * then only the rows without problems are imported. The app reads the file and sends the cells as text.
 */
@Service
@RequiredArgsConstructor
public class ImportService {

    /** One product row, cells as text (the template's columns). */
    public record ProductRow(int row, String nameEn, String nameKm, String sku, String barcode, String category, String unit,
                             String sellPrice, String reorderLevel, String leadTimeDays, String trackExpiry,
                             String packUnit, String packFactor, String packBarcode, String packPrice) {
    }

    /** One opening stock row: the product by SKU or barcode, how much, its cost, and its batch. */
    public record StockRow(int row, String sku, String barcode, String qty, String unit, String unitCost, String batchNo,
                           String expiryDate) {
    }

    public record RowResult(int row, boolean ok, List<String> errors, List<String> notes, String label) {
    }

    public record ImportResult(int total, int valid, int imported, String document, List<RowResult> rows) {
    }

    private static final Set<String> YES = Set.of("yes", "y", "true", "1", "x", "បាទ", "ចាស");
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/yyyy"), DateTimeFormatter.ofPattern("d-M-yyyy"));

    private final ProductService productService;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final UnitRepository units;
    private final EntitlementService entitlements;
    private final AdjustmentService adjustments;
    private final Messages messages;

    // ---------------------------------------------------------------- products

    public ImportResult products(List<ProductRow> rows, boolean dryRun) {
        Map<String, Unit> unitsBySymbol = unitsBySymbol();
        Map<String, Category> categoriesByName = new HashMap<>();
        categories.findAll().forEach(c -> {
            categoriesByName.put(key(c.getNameEn()), c);
            if (c.getNameKm() != null) {
                categoriesByName.put(key(c.getNameKm()), c);
            }
        });
        Set<String> skus = new HashSet<>();
        Set<String> barcodes = new HashSet<>();
        Integer limit = entitlements.effectivePlan(BusinessContext.businessId()).limit(EntitlementService.PRODUCTS);
        long room = limit == null ? Long.MAX_VALUE : limit - entitlements.usage(BusinessContext.businessId(), EntitlementService.PRODUCTS);

        List<RowResult> results = new ArrayList<>();
        List<ProductRequest> toCreate = new ArrayList<>();
        List<Integer> createdRows = new ArrayList<>();
        Map<Integer, String> newCategoryOf = new HashMap<>();
        for (ProductRow r : rows) {
            List<String> errors = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            String name = text(r.nameEn());
            if (name == null) {
                errors.add(msg("IMPORT_REQUIRED", "nameEn"));
            } else if (name.length() > 160) {
                errors.add(msg("IMPORT_TOO_LONG", "nameEn", 160));
            }
            BigDecimal price = number(r.sellPrice(), "sellPrice", true, errors);
            BigDecimal reorder = number(r.reorderLevel(), "reorderLevel", false, errors);
            BigDecimal lead = number(r.leadTimeDays(), "leadTimeDays", false, errors);
            Unit base = unit(text(r.unit()) == null ? "pcs" : text(r.unit()), unitsBySymbol, errors);
            String sku = text(r.sku());
            if (sku != null && (!skus.add(key(sku)) || products.existsBySkuIgnoreCase(sku))) {
                errors.add(msg("SKU_TAKEN", sku));
            } else if (sku == null) {
                notes.add(msg("IMPORT_SKU_AUTO"));
            }
            String barcode = text(r.barcode());
            if (barcode != null && (!barcodes.add(barcode) || barcodeUsed(barcode))) {
                errors.add(msg("BARCODE_TAKEN", barcode));
            }
            Long categoryId = null;
            String category = text(r.category());
            if (category != null) {
                Category c = categoriesByName.get(key(category));
                if (c != null) {
                    categoryId = c.getId();
                } else {
                    notes.add(msg("IMPORT_NEW_CATEGORY", category));
                    newCategoryOf.put(r.row(), category);
                }
            }
            List<ProductUnitRequest> packs = new ArrayList<>();
            String packUnit = text(r.packUnit());
            BigDecimal factor = number(r.packFactor(), "packFactor", false, errors);
            if (packUnit != null || factor != null) {
                Unit pack = packUnit == null ? null : unit(packUnit, unitsBySymbol, errors);
                if (packUnit == null || factor == null || factor.compareTo(BigDecimal.ONE) <= 0
                        || (pack != null && base != null && pack.getId().equals(base.getId()))) {
                    errors.add(msg("IMPORT_PACK"));
                } else if (pack != null) {
                    String packBarcode = text(r.packBarcode());
                    if (packBarcode != null && (!barcodes.add(packBarcode) || barcodeUsed(packBarcode))) {
                        errors.add(msg("BARCODE_TAKEN", packBarcode));
                    }
                    packs.add(new ProductUnitRequest(pack.getId(), factor, packBarcode, number(r.packPrice(), "packPrice", false, errors)));
                }
            }
            if (errors.isEmpty() && room <= 0) {
                errors.add(msg("PLAN_LIMIT_REACHED", "products", limit));
            }
            boolean ok = errors.isEmpty();
            if (ok) {
                room--;
                toCreate.add(new ProductRequest(sku, barcode, name, text(r.nameKm()), categoryId, base.getId(), price, reorder,
                        lead == null ? null : lead.intValue(), YES.contains(key(r.trackExpiry())), false, packs));
                createdRows.add(r.row());
            }
            results.add(new RowResult(r.row(), ok, errors, notes, name));
        }

        int imported = 0;
        if (!dryRun) {
            Map<String, Long> made = new HashMap<>();
            for (int i = 0; i < toCreate.size(); i++) {
                ProductRequest req = toCreate.get(i);
                int row = createdRows.get(i);
                String newCategory = newCategoryOf.get(row);
                if (newCategory != null) {
                    Long id = made.computeIfAbsent(key(newCategory), k -> {
                        Category c = new Category();
                        c.setNameEn(newCategory);
                        return categories.save(c).getId();
                    });
                    req = withCategory(req, id);
                }
                try {
                    productService.create(req);
                    imported++;
                } catch (ApiException e) {
                    replace(results, row, msg(e.getCode(), e.getArgs()));
                }
            }
        }
        return new ImportResult(rows.size(), createdRows.size(), imported, null, results);
    }

    // ---------------------------------------------------------------- opening stock

    public ImportResult openingStock(Long warehouseId, List<StockRow> rows, boolean dryRun) {
        Map<String, Unit> unitsBySymbol = unitsBySymbol();
        List<RowResult> results = new ArrayList<>();
        List<AdjustmentLineRequest> lines = new ArrayList<>();
        for (StockRow r : rows) {
            List<String> errors = new ArrayList<>();
            String code = text(r.sku()) != null ? text(r.sku()) : text(r.barcode());
            Product p = null;
            Long packUnitId = null;
            if (code == null) {
                errors.add(msg("IMPORT_REQUIRED", "sku"));
            } else {
                p = (text(r.sku()) != null ? products.findBySkuIgnoreCase(code) : products.findByBarcode(code))
                        .or(() -> products.findByBarcode(code)).filter(Product::isActive).orElse(null);
                if (p == null) {
                    // A pack's own barcode (a box): the quantity is in boxes unless the row names a unit.
                    p = products.findByUnitBarcode(code).filter(Product::isActive).orElse(null);
                    if (p != null) {
                        packUnitId = productService.get(p.getId()).units().stream()
                                .filter(u -> code.equals(u.barcode())).map(ProductService.ProductUnitResponse::unitId)
                                .findFirst().orElse(null);
                    }
                }
                if (p == null) {
                    errors.add(msg("IMPORT_PRODUCT_UNKNOWN", code));
                }
            }
            BigDecimal qty = number(r.qty(), "qty", true, errors);
            if (qty != null && qty.signum() <= 0) {
                errors.add(msg("QTY_MUST_BE_POSITIVE"));
            }
            BigDecimal cost = number(r.unitCost(), "unitCost", true, errors);
            Long unitId = packUnitId;
            String unit = text(r.unit());
            if (unit != null && p != null) {
                Unit u = unit(unit, unitsBySymbol, errors);
                Product product = p;
                if (u != null && !u.getId().equals(p.getBaseUnitId())
                        && productService.get(p.getId()).units().stream().noneMatch(pu -> pu.unitId().equals(u.getId()))) {
                    errors.add(msg("IMPORT_UNIT_NOT_OF_PRODUCT", unit, product.getNameEn()));
                } else if (u != null) {
                    unitId = u.getId();
                }
            }
            LocalDate expiry = date(r.expiryDate(), errors);
            if (p != null && p.isTrackExpiry() && expiry == null && text(r.expiryDate()) == null) {
                errors.add(msg("EXPIRY_REQUIRED", p.getNameEn()));
            }
            boolean ok = errors.isEmpty();
            if (ok) {
                lines.add(new AdjustmentLineRequest(p.getId(), unitId, qty, cost, null, text(r.batchNo()), expiry));
            }
            results.add(new RowResult(r.row(), ok, errors, List.of(), p != null ? p.getNameEn() : code));
        }
        String document = null;
        if (!dryRun && !lines.isEmpty()) {
            document = adjustments.create(new AdjustmentRequest(warehouseId, StockAdjustment.Kind.OPENING, null,
                    msg("IMPORT_NOTE"), lines)).number();
        }
        return new ImportResult(rows.size(), lines.size(), dryRun ? 0 : lines.size(), document, results);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Unit> unitsBySymbol() {
        Map<String, Unit> map = new HashMap<>();
        units.findAllByOrderByIdAsc().forEach(u -> {
            map.put(key(u.getSymbol()), u);
            map.put(key(u.getNameEn()), u);
            if (u.getNameKm() != null) {
                map.put(key(u.getNameKm()), u);
            }
        });
        return map;
    }

    private Unit unit(String symbol, Map<String, Unit> unitsBySymbol, List<String> errors) {
        Unit u = unitsBySymbol.get(key(symbol));
        if (u == null) {
            errors.add(msg("IMPORT_UNIT_UNKNOWN", symbol));
        }
        return u;
    }

    private boolean barcodeUsed(String barcode) {
        return products.findByBarcode(barcode).isPresent() || products.findByUnitBarcode(barcode).isPresent();
    }

    private BigDecimal number(String value, String column, boolean required, List<String> errors) {
        String t = text(value);
        if (t == null) {
            if (required) {
                errors.add(msg("IMPORT_REQUIRED", column));
            }
            return null;
        }
        try {
            BigDecimal n = new BigDecimal(t.replace("$", "").replace(",", "").replace("៛", "").trim());
            if (n.signum() < 0) {
                errors.add(msg("IMPORT_NUMBER", column));
                return null;
            }
            return n;
        } catch (NumberFormatException e) {
            errors.add(msg("IMPORT_NUMBER", column));
            return null;
        }
    }

    private LocalDate date(String value, List<String> errors) {
        String t = text(value);
        if (t == null) {
            return null;
        }
        String day = t.length() > 10 && t.charAt(10) == 'T' ? t.substring(0, 10) : t; // an Excel date sent as ISO
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(day, f);
            } catch (DateTimeParseException e) {
                // next format
            }
        }
        errors.add(msg("IMPORT_DATE", "expiryDate"));
        return null;
    }

    private static ProductRequest withCategory(ProductRequest r, Long categoryId) {
        return new ProductRequest(r.sku(), r.barcode(), r.nameEn(), r.nameKm(), categoryId, r.baseUnitId(), r.sellPrice(),
                r.reorderLevel(), r.leadTimeDays(), r.trackExpiry(), r.vatExempt(), r.units());
    }

    private static void replace(List<RowResult> results, int row, String error) {
        for (int i = 0; i < results.size(); i++) {
            RowResult r = results.get(i);
            if (r.row() == row) {
                results.set(i, new RowResult(row, false, List.of(error), r.notes(), r.label()));
            }
        }
    }

    private String msg(String code, Object... args) {
        return messages.get(code, args);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
