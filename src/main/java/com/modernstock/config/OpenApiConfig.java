package com.modernstock.config;

import java.util.List;
import java.util.Set;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.modernstock.business.BusinessContextFilter;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * Swagger UI at /api/v1/swagger-ui.html.
 * X-Business-Id and X-Branch-Id are declared as API-key schemes, so they are entered once in "Authorize"
 * together with the bearer token, instead of on every operation.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearer";
    static final String BUSINESS = "business";
    static final String BRANCH = "branch";

    private static final Set<String> PUBLIC_AUTH = Set.of("/auth/sign-up", "/auth/verify-email",
            "/auth/resend-verification", "/auth/login", "/auth/refresh", "/auth/logout", "/auth/forgot-password",
            "/auth/reset-password", "/auth/sign-up-with-invitation");

    private static final String DESCRIPTION = """
            REST API for **ModernStock**, a stock management system for small businesses in Cambodia.

            **How to try it**
            1. `POST /auth/login` with the demo account `owner@demo.com` / `Demo@1234` (dev profile), or sign up.
            2. Copy `accessToken`, click **Authorize**, paste it into **bearer**.
            3. `GET /me/businesses`, then put the business `id` into **business** (`X-Business-Id`).
               Optionally put a branch ID, or `all`, into **branch** (`X-Branch-Id`).

            **Conventions**
            - Money is USD with 2 decimals; riel amounts are whole numbers. Quantities are in the product's base unit
              unless a `unitId` is given.
            - Lists take `page`, `size` and `sort` (for example `sort=nameEn,asc`) and return
              `content`, `page`, `size`, `totalElements`, `totalPages`.
            - Errors are RFC 7807 `ProblemDetail` with a `code` (for example `INSUFFICIENT_STOCK`) and a message in
              the `Accept-Language` language (`km` or `en`).
            - Posted documents are never edited or deleted; they are voided or returned.
            """;

    /** Display order of the groups in Swagger UI. */
    private static final List<String[]> TAGS = List.of(
            new String[] {"Auth", "Sign up, log in, tokens, passwords"},
            new String[] {"My account", "Profile, my businesses, my invitations (no X-Business-Id)"},
            new String[] {"Public", "Website data and invitation links (no token)"},
            new String[] {"Business", "Business profile, setup, settings"},
            new String[] {"Branches", "Branches and their warehouses"},
            new String[] {"Exchange rates", "Daily USD to KHR rate"},
            new String[] {"Members", "Members, roles, invitations"},
            new String[] {"Products", "Products, packaging units, barcode lookup"},
            new String[] {"Categories and units", "Catalog master data"},
            new String[] {"Suppliers", "Suppliers and what you owe them"},
            new String[] {"Customers", "Customers and what they owe"},
            new String[] {"Purchases", "Purchase orders, receiving goods, bills, supplier payments"},
            new String[] {"POS", "Counter sales: checkout, sales receipts, invoices, payments received"},
            new String[] {"Cash shifts", "Opening and closing the cash drawer in USD and KHR"},
            new String[] {"Stock", "Stock levels, stock card, batches"},
            new String[] {"Adjustments", "Opening stock and stock adjustments"},
            new String[] {"Transfers", "Moves inside a branch and transfers between branches"},
            new String[] {"Billing", "Plan, limits and usage (Owner only)"});

    @Bean
    OpenAPI modernStockOpenApi() {
        Components components = new Components()
                .addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .bearerFormat("JWT").description("Access token from POST /auth/login (valid 15 minutes)"))
                .addSecuritySchemes(BUSINESS, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER).name(BusinessContextFilter.BUSINESS_HEADER)
                        .description("ID of the open business, from GET /me/businesses"))
                .addSecuritySchemes(BRANCH, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER).name(BusinessContextFilter.BRANCH_HEADER)
                        .description("Branch selected in the top bar: a branch ID, or `all`. Optional"));
        return new OpenAPI()
                .info(new Info().title("ModernStock API").version("v1").description(DESCRIPTION)
                        .contact(new Contact().name("ModernStock thesis team")))
                .components(components)
                .tags(TAGS.stream().map(t -> new Tag().name(t[0]).description(t[1])).toList());
    }

    /** Sets the right security on each path: none for public ones, the token for account ones, all three for business ones. */
    @Bean
    OpenApiCustomizer securityByPath() {
        SecurityRequirement account = new SecurityRequirement().addList(BEARER);
        SecurityRequirement business = new SecurityRequirement().addList(BEARER).addList(BUSINESS).addList(BRANCH);
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
                if (isPublic(path)) {
                    op.setSecurity(List.of());
                } else if (path.startsWith("/auth/") || path.equals("/me") || path.startsWith("/me/")
                        || path.startsWith("/admin/")) {
                    op.setSecurity(List.of(account));
                } else {
                    op.setSecurity(List.of(business));
                }
            }));
        };
    }

    private static boolean isPublic(String path) {
        return path.startsWith("/public/") || PUBLIC_AUTH.contains(path);
    }
}
