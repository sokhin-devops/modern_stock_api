package com.modernstock.publicsite;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.billing.Plan;
import com.modernstock.billing.PlanRepository;
import com.modernstock.member.InvitationService;
import com.modernstock.member.InvitationService.InvitationDetails;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@Tag(name = "Public")
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicController {

    /** limits: null = unlimited, 0/1 for features. */
    public record PlanResponse(String code, String name, BigDecimal priceMonthly, BigDecimal priceYearly,
                               Map<String, Integer> limits) {
    }

    public record ContactRequest(@NotBlank @Size(max = 120) String name,
                                 @Size(max = 20) String phone,
                                 @Email @Size(max = 160) String email,
                                 @NotBlank @Size(max = 2000) String message,
                                 @Pattern(regexp = "km|en") String language) {
    }

    private final PlanRepository plans;
    private final ContactMessageRepository contactMessages;
    private final InvitationService invitations;
    private final ApplicationEventPublisher events;

    @Operation(summary = "Plans for the Pricing page", description = "The website reads prices from here, so they always match billing.")
    @GetMapping("/plans")
    @Transactional(readOnly = true)
    public List<PlanResponse> publicPlans() {
        return plans.findByPublicPlanTrueOrderBySortOrder().stream().map(PublicController::toResponse).toList();
    }

    @Operation(summary = "Send a message from the website contact form",
            description = "Saved for the super admin (Admin › Messages) and forwarded to the team's Telegram chat when one is set.")
    @PostMapping("/contact")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public void sendContactMessage(@Valid @RequestBody ContactRequest req) {
        ContactMessage m = new ContactMessage();
        m.setName(req.name());
        m.setPhone(req.phone());
        m.setEmail(req.email());
        m.setMessage(req.message());
        m.setLanguage(req.language() == null ? "km" : req.language());
        contactMessages.save(m);
        events.publishEvent(new ContactForwarder.ContactReceived(m.getId()));
    }

    @Operation(summary = "Invitation details for the invitation page",
            description = "Shows the business, role and branch before the person joins. The token is the value from the link.")
    @GetMapping("/invitations/{token}")
    public InvitationDetails invitationDetails(@PathVariable String token) {
        return invitations.details(token);
    }

    private static PlanResponse toResponse(Plan p) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        p.getLimits().forEach(l -> limits.put(l.getLimitKey(), l.getValue()));
        return new PlanResponse(p.getCode(), p.getName(), p.getPriceMonthly(), p.getPriceYearly(), limits);
    }
}
