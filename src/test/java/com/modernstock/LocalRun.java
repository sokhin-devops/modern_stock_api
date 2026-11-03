package com.modernstock;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs the API with its own PostgreSQL, so nothing needs installing:
 * <pre>mvn spring-boot:test-run</pre>
 * <ul>
 *   <li>Port: PORT if set, otherwise 8080, or the next free port up to 8099 when 8080 is taken.
 *       The Swagger link is printed when the API is ready.</li>
 *   <li>Database: .data/postgres (port 54320, user postgres / postgres). It survives restarts;
 *       delete the folder to start fresh.</li>
 *   <li>The dev profile adds the demo account owner@demo.com / Demo@1234.</li>
 * </ul>
 * With Docker or your own PostgreSQL, use "mvn spring-boot:run" and DB_URL / DB_USERNAME / DB_PASSWORD instead.
 */
public final class LocalRun {

    private static final int DB_PORT = 54320;

    private LocalRun() {
    }

    public static void main(String[] args) throws Exception {
        int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : firstFreePort(8080, 8099);
        if (!isFree(DB_PORT)) {
            throw new IllegalStateException("Port " + DB_PORT + " is in use: is LocalRun already running in another terminal?");
        }

        Path data = Path.of(".data", "postgres");
        Files.createDirectories(data);
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(DB_PORT)
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

        System.setProperty("server.port", String.valueOf(port));
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        ConfigurableApplicationContext app = SpringApplication.run(ModernStockApplication.class, args);

        String base = "http://localhost:" + port + "/api/v1";
        System.out.println("""

                ==================================================================
                 ModernStock API is ready
                 Swagger UI : %s/swagger-ui.html
                 API base   : %s
                 Demo login : owner@demo.com / Demo@1234   (cashier: dara / Demo@1234)
                 Database   : localhost:%d, user postgres / postgres (.data/postgres)
                 Stop       : Ctrl+C
                ==================================================================
                """.formatted(base, base, DB_PORT));
        app.registerShutdownHook();
    }

    private static int firstFreePort(int from, int to) {
        for (int p = from; p <= to; p++) {
            if (isFree(p)) {
                if (p != from) {
                    System.out.println("Port " + from + " is in use, so the API uses port " + p + ".");
                }
                return p;
            }
        }
        throw new IllegalStateException("No free port between " + from + " and " + to + ". Set PORT to a free port.");
    }

    private static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
