package com.modernstock;

import java.nio.file.Files;
import java.nio.file.Path;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs the API on http://localhost:8080 with its own PostgreSQL, so nothing needs installing:
 * <pre>mvn spring-boot:test-run</pre>
 * The database lives in .data/postgres (port 54320, user postgres / postgres) and survives restarts;
 * delete the folder to start fresh. The dev profile adds the demo account owner@demo.com / Demo@1234.
 * With Docker or your own PostgreSQL, use "mvn spring-boot:run" and DB_URL / DB_USERNAME / DB_PASSWORD instead.
 */
public final class LocalRun {

    private LocalRun() {
    }

    public static void main(String[] args) throws Exception {
        Path data = Path.of(".data", "postgres");
        Files.createDirectories(data);
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(54320)
                .setDataDirectory(data)
                .setCleanDataDirectory(false)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (Exception ignored) {
                // the JVM is exiting anyway
            }
        }));
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        ModernStockApplication.main(args);
    }
}
