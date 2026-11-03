package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.modernstock.support.ApiTest;

/**
 * Checks the Swagger document and saves it as docs/openapi.yaml, so the web team and the thesis appendix
 * have the contract without running the API. Run the tests again after changing an endpoint.
 */
class OpenApiExportTest extends ApiTest {

    @Test
    void exportOpenApi() throws Exception {
        String yaml = rest.getForObject("/v3/api-docs.yaml", String.class);
        assertThat(yaml).contains("title: ModernStock API")
                .contains("/pos/checkout:")
                .contains("/transfers/{id}/receive:")
                .contains("X-Business-Id")
                .doesNotContainPattern("operationId: \\w+_\\d+");
        Path out = projectDir().resolve("docs").resolve("openapi.yaml");
        Files.createDirectories(out.getParent());
        Files.writeString(out, yaml, StandardCharsets.UTF_8);

        String ui = rest.getForObject("/swagger-ui/index.html", String.class);
        assertThat(ui).contains("Swagger UI");
    }

    /** Maven runs tests in the project folder; an IDE may run them from the parent workspace folder. */
    private static Path projectDir() {
        Path here = Path.of("").toAbsolutePath();
        return Files.exists(here.resolve("modern_stock_api").resolve("pom.xml")) ? here.resolve("modern_stock_api") : here;
    }
}
