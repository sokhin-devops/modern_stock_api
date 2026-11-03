package com.modernstock.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.modernstock.auth.User;
import com.modernstock.auth.UserRepository;
import com.modernstock.business.BranchService;
import com.modernstock.business.BranchService.BranchRequest;
import com.modernstock.business.BranchService.WarehouseRequest;
import com.modernstock.business.Business;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.BusinessMember;
import com.modernstock.business.BusinessMemberRepository;
import com.modernstock.business.BusinessService;
import com.modernstock.business.BusinessService.CreateBusinessRequest;
import com.modernstock.business.BusinessService.OnboardingRequest;
import com.modernstock.catalog.ProductService;
import com.modernstock.catalog.ProductService.ProductRequest;
import com.modernstock.catalog.ProductService.ProductResponse;
import com.modernstock.catalog.ProductService.ProductUnitRequest;
import com.modernstock.catalog.UnitRepository;
import com.modernstock.common.Dates;
import com.modernstock.inventory.AdjustmentService;
import com.modernstock.inventory.AdjustmentService.AdjustmentLineRequest;
import com.modernstock.inventory.AdjustmentService.AdjustmentRequest;
import com.modernstock.inventory.StockAdjustment;
import com.modernstock.member.Role;
import com.modernstock.member.RoleRepository;
import com.modernstock.partner.Customer;
import com.modernstock.partner.CustomerRepository;
import com.modernstock.partner.Supplier;
import com.modernstock.partner.SupplierRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Development only (app.demo-data=true): a ready business to try in Swagger UI.
 * Owner owner@demo.com / Demo@1234, cashier "dara" / Demo@1234, "Sokha Mini-mart" with two branches.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.demo-data", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    public static final String OWNER_EMAIL = "owner@demo.com";
    public static final String PASSWORD = "Demo@1234";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BusinessService businesses;
    private final BusinessMemberRepository members;
    private final RoleRepository roles;
    private final BranchService branches;
    private final UnitRepository units;
    private final ProductService products;
    private final AdjustmentService adjustments;
    private final SupplierRepository suppliers;
    private final CustomerRepository customers;
    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        if (users.findByEmailIgnoreCase(OWNER_EMAIL).isPresent()) {
            // the cashier is created last: if it is missing, an earlier start stopped half-way
            if (!users.existsByUsernameIgnoreCase("dara")) {
                log.warn("Demo data is incomplete (an earlier start failed while seeding). "
                        + "Stop the API, delete the .data folder (or the database) and start again.");
            }
            return;
        }
        User owner = user("Sokha Chan", OWNER_EMAIL, null, null);
        actAs(owner);
        try {
            Business b = businesses.create(owner.getId(),
                    new CreateBusinessRequest("Sokha Mini-mart", "សុខា មីនីម៉ាត", "MINI_MART", "012 345 678", "km"));
            BusinessContext.Context ctx = new BusinessContext.Context(b.getId(), owner.getId(), null, Role.OWNER,
                    null, null, new HashSet<>(jdbc.queryForList("SELECT code FROM permissions", String.class)));
            BusinessContext.runAs(ctx, () -> {
                seed(b, owner);
                return null;
            });
            log.info("Demo data ready: {} / {} (business {}), cashier dara / {}", OWNER_EMAIL, PASSWORD, b.getId(), PASSWORD);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void seed(Business b, User owner) {
        businesses.onboard(new OnboardingRequest(
                new BranchRequest("TK", "Toul Kork", "ទួលគោក", "St. 315, Toul Kork", "012 345 678", "Phnom Penh"),
                new BigDecimal("4100"), false, 100));
        var main = branches.list().get(0);
        branches.addWarehouse(main.id(), new WarehouseRequest("Back store", "ឃ្លាំងក្រោយ", false, true, true));
        branches.create(new BranchRequest("SS", "Sen Sok", "សែនសុខ", "Street 1986, Sen Sok", "015 222 333", "Phnom Penh"));

        Map<String, Long> unit = units.findAll().stream()
                .collect(Collectors.toMap(u -> u.getSymbol(), u -> u.getId()));
        ProductResponse water = product("Drinking water 500ml", "ទឹកសុទ្ធ ៥០០មល", "8850001000017", unit.get("pcs"),
                "0.50", "24", List.of(new ProductUnitRequest(unit.get("box"), new BigDecimal("24"), "8850001000024", new BigDecimal("10.00"))), false);
        ProductResponse coke = product("Coca-Cola can 330ml", "កូកាកូឡា កំប៉ុង", "8850002000016", unit.get("can"),
                "0.75", "24", List.of(new ProductUnitRequest(unit.get("box"), new BigDecimal("24"), null, new BigDecimal("16.50"))), false);
        ProductResponse noodles = product("Instant noodles", "មីកញ្ចប់", "8850003000015", unit.get("pack"),
                "0.40", "30", List.of(new ProductUnitRequest(unit.get("box"), new BigDecimal("30"), null, null)), false);
        ProductResponse rice = product("Jasmine rice 5kg", "អង្ករផ្កាម្លិះ ៥គីឡូ", "8850004000014", unit.get("pcs"),
                "12.35", "5", List.of(), false);
        ProductResponse paracetamol = product("Paracetamol 500mg (10 tablets)", "ប៉ារ៉ាសេតាមុល", "8850005000013",
                unit.get("pack"), "1.00", "10", List.of(), true);

        Long backStore = branches.list().get(0).warehouses().stream().filter(w -> w.receiving()).findFirst().orElseThrow().id();
        Long shopFloor = branches.list().get(0).warehouses().stream().filter(w -> w.sales()).findFirst().orElseThrow().id();
        adjustments.create(new AdjustmentRequest(shopFloor, StockAdjustment.Kind.OPENING, null, "Opening stock", List.of(
                new AdjustmentLineRequest(water.id(), null, new BigDecimal("120"), new BigDecimal("0.25"), null, null, null),
                new AdjustmentLineRequest(coke.id(), null, new BigDecimal("48"), new BigDecimal("0.45"), null, null, null),
                new AdjustmentLineRequest(noodles.id(), null, new BigDecimal("60"), new BigDecimal("0.22"), null, null, null),
                new AdjustmentLineRequest(rice.id(), null, new BigDecimal("8"), new BigDecimal("9.80"), null, null, null),
                new AdjustmentLineRequest(paracetamol.id(), null, new BigDecimal("12"), new BigDecimal("0.55"), null,
                        "PCM-2611", Dates.today().plusDays(20)))));
        adjustments.create(new AdjustmentRequest(backStore, StockAdjustment.Kind.OPENING, null, "Opening stock", List.of(
                new AdjustmentLineRequest(water.id(), unit.get("box"), new BigDecimal("10"), new BigDecimal("6.00"), null, null, null))));

        Supplier lucky = new Supplier();
        lucky.setName("Lucky Wholesale");
        lucky.setPhone("023 456 789");
        lucky.setPaymentTermsDays(15);
        suppliers.save(lucky);

        Customer sophea = new Customer();
        sophea.setName("Sophea Restaurant");
        sophea.setType(Customer.Type.COMPANY);
        sophea.setPhone("097 888 1234");
        sophea.setCreditLimit(new BigDecimal("200"));
        sophea.setPaymentTermsDays(7);
        customers.save(sophea);

        User dara = user("Dara Sok", null, "dara", b.getId());
        BusinessMember cashier = new BusinessMember();
        cashier.setBusinessId(b.getId());
        cashier.setUserId(dara.getId());
        cashier.setRoleId(roles.findBuiltIn("CASHIER").orElseThrow().getId());
        cashier.setBranchId(main.id());
        cashier.setStatus(BusinessMember.Status.ACTIVE);
        cashier.setInvitedBy(owner.getId());
        members.save(cashier);
    }

    private ProductResponse product(String nameEn, String nameKm, String barcode, Long baseUnitId, String price,
                                    String reorder, List<ProductUnitRequest> packs, boolean trackExpiry) {
        return products.create(new ProductRequest(null, barcode, nameEn, nameKm, null, baseUnitId,
                new BigDecimal(price), new BigDecimal(reorder), 3, trackExpiry, false, packs));
    }

    private User user(String name, String email, String username, Long managedByBusinessId) {
        User u = new User();
        u.setFullName(name);
        u.setEmail(email);
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setStatus(User.Status.ACTIVE);
        u.setEmailVerifiedAt(email == null ? null : Instant.now());
        u.setManagedByBusinessId(managedByBusinessId);
        return users.save(u);
    }

    /** Lets audit columns and services see the owner as the current account while seeding. */
    private static void actAs(User u) {
        Jwt jwt = Jwt.withTokenValue("seed").header("alg", "none").subject(u.getId().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), u.getId().toString()));
    }
}
