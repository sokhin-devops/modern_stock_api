package com.modernstock.business;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            @Size(max = 255) String receiptFooter) {
    }

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("vat_enabled", "false");
        DEFAULTS.put("vat_rate", "10");
        DEFAULTS.put("riel_rounding", "100");
        DEFAULTS.put("max_discount_percent", "10");
        DEFAULTS.put("allow_negative_stock", "false");
        DEFAULTS.put("receipt_footer", "សូមអរគុណ! Thank you!");
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
                values.get("receipt_footer"));
    }

    @Transactional
    public BusinessSettings update(BusinessSettings s) {
        if (s.rielRounding() != 100 && s.rielRounding() != 500 && s.rielRounding() != 1000) {
            throw com.modernstock.common.ApiException.badRequest("RIEL_ROUNDING_INVALID");
        }
        put("vat_enabled", String.valueOf(s.vatEnabled()));
        put("vat_rate", s.vatRate().toPlainString());
        put("riel_rounding", String.valueOf(s.rielRounding()));
        put("max_discount_percent", s.maxDiscountPercent().toPlainString());
        put("allow_negative_stock", String.valueOf(s.allowNegativeStock()));
        put("receipt_footer", s.receiptFooter() == null ? "" : s.receiptFooter());
        return get();
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
