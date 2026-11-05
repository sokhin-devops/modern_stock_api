package com.modernstock.business;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.FileStorage;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Business-wide settings stored as key/value rows, with defaults for keys never saved. */
@Service
@RequiredArgsConstructor
public class SettingsService {

    public record BusinessSettings(
            @Schema(description = "VAT 10% on sales") boolean vatEnabled,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal vatRate,
            @Schema(description = "Round riel amounts to 100, 500 or 1000", allowableValues = {"100", "500", "1000"})
            int rielRounding,
            @Schema(description = "Biggest discount a cashier may give without sale:override, in % of the subtotal")
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal maxDiscountPercent,
            @Schema(description = "Blocked by default (PLAIN.md 11.2)") boolean allowNegativeStock,
            @Size(max = 255) String receiptFooter,
            @Schema(description = "Under the shop's name on receipts") @Size(max = 255) String receiptHeader,
            @Schema(description = "Receipt paper width in mm", allowableValues = {"58", "80"}) int receiptPaper,
            @Schema(description = "Print the business VAT TIN on receipts") boolean receiptShowVatTin,
            @Schema(description = "Customers may pay by KHQR (cash is always on)") boolean khqrEnabled,
            @Schema(description = "Customers may pay by bank transfer") boolean bankEnabled,
            @Schema(description = "Near-expiry windows in days (FR-7.10): three, each longer than the one before, up to 365; "
                    + "the first one warns. Leave out to keep them.", example = "[30, 60, 90]")
            List<Integer> expiryDays,
            @Schema(description = "Document number prefix per type (FR-7.11), e.g. {\"INV\": \"HD\"}: 1 to 6 capital letters or "
                    + "digits, each different. The type's code by default. Leave out, or leave a type out, to keep it.")
            Map<String, String> numberPrefixes,
            @Schema(description = "Read only: the shop's KHQR image (/public/files/...), shown at the POS", accessMode = Schema.AccessMode.READ_ONLY)
            String khqrImageUrl) {
    }

    public static final String KHQR_IMAGE = "khqr_image";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("vat_enabled", "false");
        DEFAULTS.put("vat_rate", "10");
        DEFAULTS.put("riel_rounding", "100");
        DEFAULTS.put("max_discount_percent", "10");
        DEFAULTS.put("allow_negative_stock", "false");
        DEFAULTS.put("receipt_footer", "សូមអរគុណ! Thank you!");
        DEFAULTS.put("receipt_header", "");
        DEFAULTS.put("receipt_paper", "80");
        DEFAULTS.put("receipt_show_vat_tin", "true");
        DEFAULTS.put("payment_khqr", "true");
        DEFAULTS.put("payment_bank", "true");
        DEFAULTS.put(KHQR_IMAGE, "");
        DEFAULTS.put(ExpiryWindows.KEY, ExpiryWindows.format(ExpiryWindows.DEFAULT));
    }

    private final BusinessSettingRepository repository;

    @Transactional(readOnly = true)
    public BusinessSettings get() {
        Map<String, String> values = new HashMap<>(DEFAULTS);
        repository.findAll().forEach(s -> values.put(s.getKey(), s.getValue()));
        return new BusinessSettings(
                Boolean.parseBoolean(values.get("vat_enabled")),
                new BigDecimal(values.get("vat_rate")),
                Integer.parseInt(values.get("riel_rounding")),
                new BigDecimal(values.get("max_discount_percent")),
                Boolean.parseBoolean(values.get("allow_negative_stock")),
                values.get("receipt_footer"),
                values.get("receipt_header"),
                Integer.parseInt(values.get("receipt_paper")),
                Boolean.parseBoolean(values.get("receipt_show_vat_tin")),
                Boolean.parseBoolean(values.get("payment_khqr")),
                Boolean.parseBoolean(values.get("payment_bank")),
                ExpiryWindows.parse(values.get(ExpiryWindows.KEY)),
                prefixes(values),
                values.get(KHQR_IMAGE).isBlank() ? null : FileStorage.url(values.get(KHQR_IMAGE)));
    }

    @Transactional
    public BusinessSettings update(BusinessSettings s) {
        if (s.rielRounding() != 100 && s.rielRounding() != 500 && s.rielRounding() != 1000) {
            throw com.modernstock.common.ApiException.badRequest("RIEL_ROUNDING_INVALID");
        }
        if (s.receiptPaper() != 58 && s.receiptPaper() != 80) {
            throw com.modernstock.common.ApiException.badRequest("RECEIPT_PAPER_INVALID");
        }
        Map<String, String> prefixes = s.numberPrefixes() == null ? null : checkPrefixes(s.numberPrefixes());
        if (s.expiryDays() != null && !ExpiryWindows.valid(s.expiryDays())) {
            throw com.modernstock.common.ApiException.badRequest("EXPIRY_WINDOWS_INVALID", ExpiryWindows.MAX_DAYS);
        }
        put("vat_enabled", String.valueOf(s.vatEnabled()));
        put("vat_rate", s.vatRate().toPlainString());
        put("riel_rounding", String.valueOf(s.rielRounding()));
        put("max_discount_percent", s.maxDiscountPercent().toPlainString());
        put("allow_negative_stock", String.valueOf(s.allowNegativeStock()));
        put("receipt_footer", s.receiptFooter() == null ? "" : s.receiptFooter());
        put("receipt_header", s.receiptHeader() == null ? "" : s.receiptHeader());
        put("receipt_paper", String.valueOf(s.receiptPaper()));
        put("receipt_show_vat_tin", String.valueOf(s.receiptShowVatTin()));
        put("payment_khqr", String.valueOf(s.khqrEnabled()));
        put("payment_bank", String.valueOf(s.bankEnabled()));
        if (s.expiryDays() != null) {
            put(ExpiryWindows.KEY, ExpiryWindows.format(s.expiryDays()));
        }
        if (prefixes != null) {
            prefixes.forEach((type, prefix) -> put(DocumentNumberService.PREFIX_KEY + type, prefix));
        }
        return get();
    }

    /** Every document type with its prefix, in the order of the types. */
    private static Map<String, String> prefixes(Map<String, String> values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (DocType t : DocType.values()) {
            String v = values.get(DocumentNumberService.PREFIX_KEY + t.name());
            map.put(t.name(), v == null || !DocumentNumberService.PREFIX.matcher(v).matches() ? t.name() : v);
        }
        return map;
    }

    /** The prefixes asked for, in capitals, with the ones left out as they are; refused when wrong or used twice. */
    private Map<String, String> checkPrefixes(Map<String, String> asked) {
        Map<String, String> all = new HashMap<>(prefixes(currentValues()));
        Map<String, String> changed = new LinkedHashMap<>();
        asked.forEach((type, prefix) -> {
            if (!all.containsKey(type)) {
                throw com.modernstock.common.ApiException.badRequest("NUMBER_PREFIX_INVALID", type);
            }
            String p = prefix == null || prefix.isBlank() ? type : prefix.strip().toUpperCase(java.util.Locale.ROOT);
            if (!DocumentNumberService.PREFIX.matcher(p).matches()) {
                throw com.modernstock.common.ApiException.badRequest("NUMBER_PREFIX_INVALID", prefix);
            }
            all.put(type, p);
            changed.put(type, p);
        });
        if (all.values().stream().distinct().count() < all.size()) {
            throw com.modernstock.common.ApiException.badRequest("NUMBER_PREFIX_TWICE");
        }
        return changed;
    }

    private Map<String, String> currentValues() {
        Map<String, String> values = new HashMap<>();
        repository.findAll().forEach(s -> values.put(s.getKey(), s.getValue()));
        return values;
    }

    /** One stored setting, for settings that are not part of BusinessSettings (Telegram). */
    @Transactional(readOnly = true)
    public String value(String key, String fallback) {
        return repository.findByKey(key).map(BusinessSetting::getValue).orElse(fallback);
    }

    @Transactional
    public void put(String key, String value) {
        BusinessSetting setting = repository.findByKey(key).orElseGet(BusinessSetting::new);
        setting.setKey(key);
        setting.setValue(value);
        repository.save(setting);
    }
}
