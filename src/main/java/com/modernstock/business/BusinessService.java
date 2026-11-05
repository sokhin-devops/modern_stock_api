package com.modernstock.business;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.modernstock.activity.ActivityService;
import com.modernstock.admin.PlatformSettings;
import com.modernstock.billing.BillingInvoice;
import com.modernstock.billing.BillingInvoiceRepository;
import com.modernstock.billing.Plan;
import com.modernstock.billing.PlanRepository;
import com.modernstock.billing.Subscription;
import com.modernstock.billing.SubscriptionRepository;
import com.modernstock.catalog.Category;
import com.modernstock.catalog.CategoryRepository;
import com.modernstock.catalog.ProductService;
import com.modernstock.catalog.Unit;
import com.modernstock.catalog.UnitRepository;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.common.FileStorage;
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
            @Schema(allowableValues = {"100", "500", "1000"}, example = "100") Integer rielRounding,
            @Schema(description = "Starter units and categories for this kind of shop (FR-6.23); empty = the business's type",
                    example = "MINI_MART") String starterType,
            @Schema(description = "Also add the starter set's sample products, to try the POS with") boolean sampleProducts) {
    }

    public record BusinessResponse(Long id, String nameEn, String nameKm, String businessType, String phone,
                                   String email, String address, String province, String vatTin, String themeColor,
                                   String language, Business.Status status, boolean onboardingCompleted,
                                   @Schema(description = "Path of the logo under the API (/public/files/...), or null") String logoUrl) {
        public static BusinessResponse from(Business b) {
            return new BusinessResponse(b.getId(), b.getNameEn(), b.getNameKm(), b.getBusinessType(), b.getPhone(),
                    b.getEmail(), b.getAddress(), b.getProvince(), b.getVatTin(), b.getThemeColor(), b.getLanguage(),
                    b.getStatus(), b.isOnboardingCompleted(), FileStorage.url(b.getLogoPath()));
        }
    }

    private final BusinessRepository businesses;
    private final ProductService products;
    private final ActivityService activity;
    private final FileStorage files;
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
    private final BillingInvoiceRepository billingInvoices;

    /** Makes the caller the Owner and starts a 30-day Pro trial. */
    @Transactional
    public Business create(Long userId, CreateBusinessRequest req) {
        checkLimit(userId);
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
        s.setTrialEndsAt(now.plus(Duration.ofDays(platformSettings.getInt(PlatformSettings.TRIAL_DAYS, PlatformSettings.DEFAULT_TRIAL_DAYS))));
        s.setCurrentPeriodStart(now);
        s.setCurrentPeriodEnd(s.getTrialEndsAt());
        subscriptions.save(s);
        return b;
    }

    /**
     * Archive the business (FR-6.11): it leaves everyone's list and its members can't open it, and nothing is deleted.
     * Only while nothing will be billed: a paid plan must be cancelled first. The Owner can restore it later.
     */
    @Transactional
    public void archive() {
        Business b = open();
        Subscription s = subscriptions.findByBusinessId(b.getId()).orElse(null);
        boolean billed = s != null && (s.getStatus() == Subscription.Status.PAST_DUE
                || (s.getStatus() == Subscription.Status.ACTIVE && !s.isCancelAtPeriodEnd())
                || (s.getStatus() == Subscription.Status.TRIALING && s.isSubscribed()));
        if (billed || !billingInvoices.findByBusinessIdAndStatus(b.getId(), BillingInvoice.Status.OPEN).isEmpty()) {
            throw ApiException.rule("BUSINESS_ARCHIVE_BILLING");
        }
        activity.record("BUSINESS_ARCHIVED", "BUSINESS", b.getId(), b.getNameEn(), null, null, null);
        b.setStatus(Business.Status.ARCHIVED);
    }

    /** Back from the archive, for its Owner, as long as the account is under the businesses limit. */
    @Transactional
    public Business restore(Long userId, Long businessId) {
        Business b = businesses.findById(businessId).filter(x -> x.getStatus() == Business.Status.ARCHIVED)
                .orElseThrow(() -> ApiException.notFound("Business"));
        if (!isOwner(userId, businessId)) {
            throw ApiException.notFound("Business");
        }
        checkLimit(userId);
        b.setStatus(b.isOnboardingCompleted() ? Business.Status.ACTIVE : Business.Status.SETTING_UP);
        activity.recordAs(businessId, userId, "BUSINESS_RESTORED", "BUSINESS", businessId, b.getNameEn());
        return b;
    }

    /** An active Owner of the business (archived ones too). */
    public boolean isOwner(Long userId, Long businessId) {
        return members.findByBusinessIdAndUserId(businessId, userId)
                .filter(m -> m.getStatus() == BusinessMember.Status.ACTIVE)
                .flatMap(m -> roles.findById(m.getRoleId()))
                .filter(r -> Role.OWNER.equals(r.getCode()))
                .isPresent();
    }

    private void checkLimit(Long userId) {
        int max = platformSettings.getInt(PlatformSettings.MAX_BUSINESSES, PlatformSettings.DEFAULT_MAX_BUSINESSES);
        if (businesses.countCreatedBy(userId) >= max) {
            throw ApiException.rule("BUSINESS_LIMIT_REACHED", max);
        }
    }

    @Transactional(readOnly = true)
    public BusinessResponse current() {
        return BusinessResponse.from(open());
    }

    /** The logo on receipts and the profile (FR-7.1); a new one replaces the old one. */
    @Transactional
    public BusinessResponse setLogo(MultipartFile file) {
        Business b = open();
        String old = b.getLogoPath();
        b.setLogoPath(files.saveImage(file, "logos"));
        files.delete(old);
        return BusinessResponse.from(b);
    }

    @Transactional
    public BusinessResponse removeLogo() {
        Business b = open();
        files.delete(b.getLogoPath());
        b.setLogoPath(null);
        return BusinessResponse.from(b);
    }

    @Transactional
    public BusinessResponse update(UpdateBusinessRequest req) {
        Business b = open();
        Map<String, Object> before = facts(b);
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
        activity.changed("PROFILE_CHANGED", "BUSINESS", b.getId(), b.getNameEn(), null, before, facts(b));
        return BusinessResponse.from(b);
    }

    private static Map<String, Object> facts(Business b) {
        return ActivityService.values("nameEn", b.getNameEn(), "nameKm", b.getNameKm(), "businessType", java.util.Objects.toString(b.getBusinessType(), null),
                "phone", b.getPhone(), "email", b.getEmail(), "address", b.getAddress(), "province", b.getProvince(),
                "vatTin", b.getVatTin(), "themeColor", b.getThemeColor(), "language", java.util.Objects.toString(b.getLanguage(), null));
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
                defaults.maxDiscountPercent(), defaults.allowNegativeStock(), defaults.receiptFooter(), defaults.receiptHeader(),
                defaults.receiptPaper(), defaults.receiptShowVatTin(), defaults.khqrEnabled(), defaults.bankEnabled(), null, null, null));
        currency.set(Dates.today(), req.usdToKhr());

        StarterSets.StarterSet set = StarterSets.forType(req.starterType() != null ? req.starterType() : b.getBusinessType());
        Map<String, Long> unitIds = new java.util.HashMap<>();
        set.units().forEach(u -> unitIds.put(u.symbol(), unit(u.nameEn(), u.nameKm(), u.symbol())));
        List<Long> categoryIds = set.categories().stream().map(c -> {
            Category category = new Category();
            category.setNameEn(c.nameEn());
            category.setNameKm(c.nameKm());
            return categories.save(category).getId();
        }).toList();
        if (req.sampleProducts()) {
            for (StarterSets.ProductSeed seed : set.products()) {
                List<ProductService.ProductUnitRequest> packs = seed.packUnit() == null ? List.of()
                        : List.of(new ProductService.ProductUnitRequest(unitIds.get(seed.packUnit()), seed.packFactor(), null, seed.packPrice()));
                products.create(new ProductService.ProductRequest(null, null, seed.nameEn(), seed.nameKm(), categoryIds.get(seed.category()),
                        unitIds.get(seed.unit()), seed.price(), seed.reorderLevel(), 3, seed.trackExpiry(), false, packs));
            }
        }

        Supplier market = new Supplier();
        market.setName("Market");
        suppliers.save(market);

        b.setOnboardingCompleted(true);
        b.setStatus(Business.Status.ACTIVE);
        return BusinessResponse.from(b);
    }

    private Long unit(String nameEn, String nameKm, String symbol) {
        Unit u = new Unit();
        u.setNameEn(nameEn);
        u.setNameKm(nameKm);
        u.setSymbol(symbol);
        return units.save(u).getId();
    }

    private Business open() {
        return businesses.findById(BusinessContext.require().businessId()).orElseThrow();
    }
}
