package com.modernstock.business;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.admin.PlatformSettings;
import com.modernstock.billing.Plan;
import com.modernstock.billing.PlanRepository;
import com.modernstock.billing.Subscription;
import com.modernstock.billing.SubscriptionRepository;
import com.modernstock.catalog.Category;
import com.modernstock.catalog.CategoryRepository;
import com.modernstock.catalog.Unit;
import com.modernstock.catalog.UnitRepository;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.common.Phones;
import com.modernstock.currency.CurrencyService;
import com.modernstock.member.Role;
import com.modernstock.member.RoleRepository;
import com.modernstock.partner.Supplier;
import com.modernstock.partner.SupplierRepository;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Creating a business takes two requests (PLAIN.md 11.12): POST /me/businesses creates it outside any business,
 * then PUT /business/onboarding writes the starter data inside the new business's context.
 */
@Service
@RequiredArgsConstructor
public class BusinessService {

    private static final String TYPES = "MINI_MART|GROCERY|PHARMACY|COSMETICS|HARDWARE|PHONE_SHOP|WHOLESALE|OTHER";

    public record CreateBusinessRequest(
            @NotBlank @Size(max = 120) String nameEn,
            @Size(max = 120) String nameKm,
            @Schema(example = "MINI_MART") @NotBlank @Pattern(regexp = TYPES) String businessType,
            @Pattern(regexp = Phones.KH, message = "{phone.invalid}") String phone,
            @Pattern(regexp = "km|en") String language) {
    }

    public record UpdateBusinessRequest(
            @NotBlank @Size(max = 120) String nameEn,
            @Size(max = 120) String nameKm,
            @NotBlank @Pattern(regexp = TYPES) String businessType,
            @Pattern(regexp = Phones.KH, message = "{phone.invalid}") String phone,
            @Email @Size(max = 160) String email,
            @Size(max = 255) String address,
            @Size(max = 60) String province,
            @Size(max = 30) String vatTin,
            @Schema(description = "Palette name or #hex", example = "#1A73E8") @Size(max = 20) String themeColor,
            @Pattern(regexp = "km|en") String language) {
    }

    public record OnboardingRequest(
            @Valid @NotNull BranchService.BranchRequest mainBranch,
            @Schema(example = "4100") @NotNull @DecimalMin("1000") @DecimalMax("10000") BigDecimal usdToKhr,
            boolean vatEnabled,
            @Schema(allowableValues = {"100", "500", "1000"}, example = "100") Integer rielRounding) {
    }

    public record BusinessResponse(Long id, String nameEn, String nameKm, String businessType, String phone,
                                   String email, String address, String province, String vatTin, String themeColor,
                                   String language, Business.Status status, boolean onboardingCompleted) {
        public static BusinessResponse from(Business b) {
            return new BusinessResponse(b.getId(), b.getNameEn(), b.getNameKm(), b.getBusinessType(), b.getPhone(),
                    b.getEmail(), b.getAddress(), b.getProvince(), b.getVatTin(), b.getThemeColor(), b.getLanguage(),
                    b.getStatus(), b.isOnboardingCompleted());
        }
    }

    private final BusinessRepository businesses;
    private final BusinessMemberRepository members;
    private final RoleRepository roles;
    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final PlatformSettings platformSettings;
    private final BranchService branches;
    private final SettingsService settings;
    private final CurrencyService currency;
    private final UnitRepository units;
    private final CategoryRepository categories;
    private final SupplierRepository suppliers;

    /** Makes the caller the Owner and starts a 30-day Pro trial. */
    @Transactional
    public Business create(Long userId, CreateBusinessRequest req) {
        int max = platformSettings.getInt(PlatformSettings.MAX_BUSINESSES, 5);
        if (businesses.countCreatedBy(userId) >= max) {
            throw ApiException.rule("BUSINESS_LIMIT_REACHED", max);
        }
        Business b = new Business();
        b.setNameEn(req.nameEn());
        b.setNameKm(req.nameKm());
        b.setBusinessType(req.businessType());
        b.setPhone(req.phone());
        b.setLanguage(req.language() == null ? "km" : req.language());
        b.setStatus(Business.Status.SETTING_UP);
        businesses.save(b);

        BusinessMember owner = new BusinessMember();
        owner.setBusinessId(b.getId());
        owner.setUserId(userId);
        owner.setRoleId(roles.findBuiltIn(Role.OWNER).orElseThrow().getId());
        owner.setStatus(BusinessMember.Status.ACTIVE);
        members.save(owner);

        Instant now = Instant.now();
        Subscription s = new Subscription();
        s.setBusinessId(b.getId());
        s.setPlanId(plans.findByCode(Plan.PRO).orElseThrow().getId());
        s.setStatus(Subscription.Status.TRIALING);
        s.setTrialEndsAt(now.plus(Duration.ofDays(platformSettings.getInt(PlatformSettings.TRIAL_DAYS, 30))));
        s.setCurrentPeriodStart(now);
        s.setCurrentPeriodEnd(s.getTrialEndsAt());
        subscriptions.save(s);
        return b;
    }

    @Transactional(readOnly = true)
    public BusinessResponse current() {
        return BusinessResponse.from(open());
    }

    @Transactional
    public BusinessResponse update(UpdateBusinessRequest req) {
        Business b = open();
        b.setNameEn(req.nameEn());
        b.setNameKm(req.nameKm());
        b.setBusinessType(req.businessType());
        b.setPhone(req.phone());
        b.setEmail(req.email());
        b.setAddress(req.address());
        b.setProvince(req.province());
        b.setVatTin(req.vatTin());
        b.setThemeColor(req.themeColor());
        if (req.language() != null) {
            b.setLanguage(req.language());
        }
        return BusinessResponse.from(b);
    }

    /** Main branch, settings, today's rate, starter units and categories, and the "Market" supplier. */
    @Transactional
    public BusinessResponse onboard(OnboardingRequest req) {
        Business b = open();
        if (b.isOnboardingCompleted()) {
            throw ApiException.conflict("ALREADY_SET_UP");
        }
        branches.createInternal(req.mainBranch(), true);

        SettingsService.BusinessSettings defaults = settings.get();
        settings.update(new SettingsService.BusinessSettings(req.vatEnabled(), defaults.vatRate(),
                req.rielRounding() == null ? defaults.rielRounding() : req.rielRounding(),
                defaults.maxDiscountPercent(), defaults.allowNegativeStock(), defaults.receiptFooter()));
        currency.set(Dates.today(), req.usdToKhr());

        unit("Piece", "ដុំ", "pcs");
        unit("Box", "ប្រអប់", "box");
        unit("Pack", "កញ្ចប់", "pack");
        unit("Bottle", "ដប", "btl");
        unit("Can", "កំប៉ុង", "can");
        unit("Kilogram", "គីឡូក្រាម", "kg");
        unit("Litre", "លីត្រ", "L");

        Category general = new Category();
        general.setNameEn("General");
        general.setNameKm("ទូទៅ");
        categories.save(general);

        Supplier market = new Supplier();
        market.setName("Market");
        suppliers.save(market);

        b.setOnboardingCompleted(true);
        b.setStatus(Business.Status.ACTIVE);
        return BusinessResponse.from(b);
    }

    private void unit(String nameEn, String nameKm, String symbol) {
        Unit u = new Unit();
        u.setNameEn(nameEn);
        u.setNameKm(nameKm);
        u.setSymbol(symbol);
        units.save(u);
    }

    private Business open() {
        return businesses.findById(BusinessContext.require().businessId()).orElseThrow();
    }
}
