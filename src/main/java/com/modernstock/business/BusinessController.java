package com.modernstock.business;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BusinessService.BusinessResponse;
import com.modernstock.business.BusinessService.OnboardingRequest;
import com.modernstock.business.BusinessService.UpdateBusinessRequest;
import com.modernstock.business.SettingsService.BusinessSettings;

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

    @Operation(summary = "The open business")
    @GetMapping
    public BusinessResponse getBusiness() {
        return businesses.current();
    }

    @Operation(summary = "Edit the business profile")
    @PutMapping
    @PreAuthorize("hasAuthority('business:manage')")
    public BusinessResponse updateBusiness(@Valid @RequestBody UpdateBusinessRequest req) {
        return businesses.update(req);
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
