package com.modernstock.partner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.BusinessContext;
import com.modernstock.common.PageResponse;
import com.modernstock.common.Phones;
import com.modernstock.money.BalanceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Suppliers and customers. Shared by all branches; their balance is what is owed today. */
@RestController
@RequiredArgsConstructor
public class PartnerController {

    public record SupplierRequest(@NotBlank @Size(max = 160) String name,
                                  @Size(max = 120) String contactPerson,
                                  @Pattern(regexp = Phones.KH, message = "{phone.invalid}") String phone,
                                  @Size(max = 60) String telegram,
                                  @Size(max = 255) String address,
                                  @Size(max = 30) String vatTin,
                                  @Min(0) Integer paymentTermsDays,
                                  @Schema(description = "What you already owed when you started") @DecimalMin("0") BigDecimal openingBalance,
                                  LocalDate openingBalanceDate) {
    }

    public record SupplierResponse(Long id, String name, String contactPerson, String phone, String telegram,
                                   String address, String vatTin, int paymentTermsDays, BigDecimal openingBalance,
                                   LocalDate openingBalanceDate, boolean active,
                                   @Schema(description = "What you owe this supplier now, USD") BigDecimal balance) {
    }

    public record CustomerRequest(@NotBlank @Size(max = 160) String name,
                                  Customer.Type type,
                                  @Pattern(regexp = Phones.KH, message = "{phone.invalid}") String phone,
                                  @Size(max = 255) String address,
                                  @Size(max = 30) String vatTin,
                                  @Schema(description = "0 = no limit set") @DecimalMin("0") BigDecimal creditLimit,
                                  @Min(0) Integer paymentTermsDays,
                                  @DecimalMin("0") BigDecimal openingBalance,
                                  LocalDate openingBalanceDate) {
    }

    public record CustomerResponse(Long id, String name, Customer.Type type, String phone, String address,
                                   String vatTin, BigDecimal creditLimit, int paymentTermsDays,
                                   BigDecimal openingBalance, LocalDate openingBalanceDate, boolean active,
                                   @Schema(description = "What the customer owes now, USD (negative = deposit)") BigDecimal balance) {
    }

    private final SupplierRepository suppliers;
    private final CustomerRepository customers;
    private final BalanceService balances;

    // ------------------------------------------------------------------ suppliers

    @Tag(name = "Suppliers")
    @Operation(summary = "Search suppliers", description = "q matches name or phone.")
    @GetMapping("/suppliers")
    @PreAuthorize("hasAuthority('supplier:view')")
    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> searchSuppliers(@RequestParam(required = false) String q,
                                                    @ParameterObject @PageableDefault(sort = "name") Pageable pageable) {
        Page<Supplier> page = suppliers.search(like(q), pageable);
        Map<Long, BigDecimal> owed = balances.suppliers(page.getContent().stream().map(Supplier::getId).toList());
        return PageResponse.of(page, s -> toResponse(s, owed.getOrDefault(s.getId(), BigDecimal.ZERO)));
    }

    @Tag(name = "Suppliers")
    @GetMapping("/suppliers/{id}")
    @PreAuthorize("hasAuthority('supplier:view')")
    @Transactional(readOnly = true)
    public SupplierResponse getSupplier(@PathVariable Long id) {
        Supplier s = suppliers.get(id);
        return toResponse(s, balances.suppliers(java.util.List.of(id)).getOrDefault(id, BigDecimal.ZERO));
    }

    @Tag(name = "Suppliers")
    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('supplier:manage')")
    @Transactional
    public SupplierResponse createSupplier(@Valid @RequestBody SupplierRequest req) {
        Supplier s = suppliers.save(apply(new Supplier(), req));
        return toResponse(s, s.getOpeningBalance());
    }

    @Tag(name = "Suppliers")
    @PutMapping("/suppliers/{id}")
    @PreAuthorize("hasAuthority('supplier:manage')")
    @Transactional
    public SupplierResponse updateSupplier(@PathVariable Long id, @Valid @RequestBody SupplierRequest req) {
        apply(suppliers.get(id), req);
        suppliers.flush();
        return getSupplier(id);
    }

    @Tag(name = "Suppliers")
    @Operation(summary = "Deactivate a supplier", description = "Kept for old documents.")
    @DeleteMapping("/suppliers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('supplier:manage')")
    @Transactional
    public void deactivateSupplier(@PathVariable Long id) {
        suppliers.get(id).setActive(false);
    }

    // ------------------------------------------------------------------ customers

    @Tag(name = "Customers")
    @Operation(summary = "Search customers", description = "q matches name or phone; the POS finds customers by phone.")
    @GetMapping("/customers")
    @PreAuthorize("hasAnyAuthority('customer:view', 'customer:create_pos')")
    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> searchCustomers(@RequestParam(required = false) String q,
                                                    @ParameterObject @PageableDefault(sort = "name") Pageable pageable) {
        Page<Customer> page = customers.search(like(q), pageable);
        Map<Long, BigDecimal> owed = balances.customers(page.getContent().stream().map(Customer::getId).toList());
        return PageResponse.of(page, c -> toResponse(c, owed.getOrDefault(c.getId(), BigDecimal.ZERO)));
    }

    @Tag(name = "Customers")
    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAnyAuthority('customer:view', 'customer:create_pos')")
    @Transactional(readOnly = true)
    public CustomerResponse getCustomer(@PathVariable Long id) {
        return toResponse(customers.get(id), balances.customer(id));
    }

    @Tag(name = "Customers")
    @Operation(summary = "Add a customer", description = "Cashiers can add a customer from the POS (customer:create_pos), "
            + "but only with name and phone: credit limit and opening balance need customer:manage.")
    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('customer:manage', 'customer:create_pos')")
    @Transactional
    public CustomerResponse createCustomer(@Valid @RequestBody CustomerRequest req) {
        Customer c = apply(new Customer(), req);
        if (!BusinessContext.has("customer:manage")) {
            c.setCreditLimit(BigDecimal.ZERO);
            c.setOpeningBalance(BigDecimal.ZERO);
            c.setOpeningBalanceDate(null);
        }
        customers.save(c);
        return toResponse(c, c.getOpeningBalance());
    }

    @Tag(name = "Customers")
    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('customer:manage')")
    @Transactional
    public CustomerResponse updateCustomer(@PathVariable Long id, @Valid @RequestBody CustomerRequest req) {
        apply(customers.get(id), req);
        customers.flush();
        return getCustomer(id);
    }

    @Tag(name = "Customers")
    @DeleteMapping("/customers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('customer:manage')")
    @Transactional
    public void deactivateCustomer(@PathVariable Long id) {
        customers.get(id).setActive(false);
    }

    private static Supplier apply(Supplier s, SupplierRequest req) {
        s.setName(req.name().trim());
        s.setContactPerson(req.contactPerson());
        s.setPhone(req.phone());
        s.setTelegram(req.telegram());
        s.setAddress(req.address());
        s.setVatTin(req.vatTin());
        s.setPaymentTermsDays(req.paymentTermsDays() == null ? 0 : req.paymentTermsDays());
        s.setOpeningBalance(req.openingBalance() == null ? BigDecimal.ZERO : req.openingBalance());
        s.setOpeningBalanceDate(req.openingBalanceDate());
        return s;
    }

    private static Customer apply(Customer c, CustomerRequest req) {
        c.setName(req.name().trim());
        c.setType(req.type() == null ? Customer.Type.INDIVIDUAL : req.type());
        c.setPhone(req.phone());
        c.setAddress(req.address());
        c.setVatTin(req.vatTin());
        c.setCreditLimit(req.creditLimit() == null ? BigDecimal.ZERO : req.creditLimit());
        c.setPaymentTermsDays(req.paymentTermsDays() == null ? 0 : req.paymentTermsDays());
        c.setOpeningBalance(req.openingBalance() == null ? BigDecimal.ZERO : req.openingBalance());
        c.setOpeningBalanceDate(req.openingBalanceDate());
        return c;
    }

    private static SupplierResponse toResponse(Supplier s, BigDecimal balance) {
        return new SupplierResponse(s.getId(), s.getName(), s.getContactPerson(), s.getPhone(), s.getTelegram(),
                s.getAddress(), s.getVatTin(), s.getPaymentTermsDays(), s.getOpeningBalance(),
                s.getOpeningBalanceDate(), s.isActive(), balance);
    }

    private static CustomerResponse toResponse(Customer c, BigDecimal balance) {
        return new CustomerResponse(c.getId(), c.getName(), c.getType(), c.getPhone(), c.getAddress(), c.getVatTin(),
                c.getCreditLimit(), c.getPaymentTermsDays(), c.getOpeningBalance(), c.getOpeningBalanceDate(),
                c.isActive(), balance);
    }

    private static String like(String q) {
        return q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase() + "%";
    }
}
