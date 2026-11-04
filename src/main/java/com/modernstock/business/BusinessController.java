package com.modernstock.business;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.modernstock.billing.EntitlementService;
import com.modernstock.business.BusinessService.BusinessResponse;
import com.modernstock.business.BusinessService.OnboardingRequest;
import com.modernstock.business.BusinessService.UpdateBusinessRequest;
import com.modernstock.business.SettingsService.BusinessSettings;
import com.modernstock.common.FileStorage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Business")
@RestController
@RequestMapping("/business")
@RequiredArgsConstructor
public class BusinessController {

    private final BusinessService businesses;
    private final SettingsService settings;
    private final EntitlementService entitlements;
    private final FileStorage files;

    @Operation(summary = "The open business")
    @GetMapping
    public BusinessResponse getBusiness() {
        return businesses.current();
    }

    @Operation(summary = "What the plan includes", description = "Plan features for any member: excel_export, telegram_alerts, "
            + "tax_invoice (screens show or explain them).")
    @GetMapping("/features")
    public Map<String, Boolean> getFeatures() {
        return Map.of(EntitlementService.EXCEL_EXPORT, entitlements.hasFeature(EntitlementService.EXCEL_EXPORT),
                EntitlementService.TELEGRAM_ALERTS, entitlements.hasFeature(EntitlementService.TELEGRAM_ALERTS),
                EntitlementService.TAX_INVOICE, entitlements.hasFeature(EntitlementService.TAX_INVOICE));
    }

    @Operation(summary = "Edit the business profile")
    @PutMapping
    @PreAuthorize("hasAuthority('business:manage')")
    public BusinessResponse updateBusiness(@Valid @RequestBody UpdateBusinessRequest req) {
        return businesses.update(req);
    }

    @Operation(summary = "Add or replace the logo", description = "JPEG, PNG or WebP up to 2 MB (form field \"file\").")
    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('business:manage')")
    public BusinessResponse uploadLogo(@RequestParam("file") MultipartFile file) {
        return businesses.setLogo(file);
    }

    @Operation(summary = "Add or replace the shop's KHQR image", description = "Shown at the POS when a customer pays by KHQR "
            + "(FR-7.7). JPEG, PNG or WebP up to 2 MB (form field \"file\").")
    @PostMapping(value = "/khqr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('business:manage')")
    public BusinessSettings uploadKhqr(@RequestParam("file") MultipartFile file) {
        String old = settings.value(SettingsService.KHQR_IMAGE, "");
        settings.put(SettingsService.KHQR_IMAGE, files.saveImage(file, "khqr"));
        files.delete(old.isBlank() ? null : old);
        return settings.get();
    }

    @Operation(summary = "Remove the KHQR image")
    @DeleteMapping("/khqr")
    @PreAuthorize("hasAuthority('business:manage')")
    public BusinessSettings removeKhqr() {
        String old = settings.value(SettingsService.KHQR_IMAGE, "");
        settings.put(SettingsService.KHQR_IMAGE, "");
        files.delete(old.isBlank() ? null : old);
        return settings.get();
    }

    @Operation(summary = "Remove the logo")
    @DeleteMapping("/logo")
    @PreAuthorize("hasAuthority('business:manage')")
    public BusinessResponse removeLogo() {
        return businesses.removeLogo();
    }

    @Operation(summary = "Finish the setup page",
            description = "Second step after POST /me/businesses. Creates the main branch (with a Main warehouse), settings, "
                    + "today's exchange rate, starter units and categories, and the \"Market\" supplier. Runs once.")
    @PutMapping("/onboarding")
    @PreAuthorize("hasAuthority('business:manage')")
    public BusinessResponse onboarding(@Valid @RequestBody OnboardingRequest req) {
        return businesses.onboard(req);
    }

    @Operation(summary = "Business settings", description = "VAT, riel rounding, discount limit, negative stock, receipt footer.")
    @GetMapping("/settings")
    public BusinessSettings getSettings() {
        return settings.get();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('business:manage')")
    public BusinessSettings updateSettings(@Valid @RequestBody BusinessSettings req) {
        return settings.update(req);
    }
}
