package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MANUAL OPT-IN HOSTED TEST, not a default Surefire *Test class.
 *
 * It creates one randomly named database on the configured isolated Aiven
 * MySQL Free service, runs a real Flyway 14->31 upgrade rehearsal, checks
 * core database objects, and drops ONLY that database in finally.
 * Never enable application/runtime MySQL readiness as a result of this test.
 *
 * Run ONLY through scripts/test-aiven-flyway-windows.ps1, which requires
 * an explicit local confirmation and never commits credentials.
 */
final class HostedAivenFlywayReview {
    @Test
    void migrateFromV14ToV31OnRemoteScratchDatabase() throws Exception {
        if (!"SCRATCH_ONLY".equals(System.getenv("SCS_M8_RUN"))) {
            throw new IllegalStateException("Refusing hosted migration: SCS_M8_RUN must be SCRATCH_ONLY");
        }

        String host = required("SCS_M8_HOST");
        if (!host.matches("(?i)^[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.aivencloud\\.com$")) {
            throw new IllegalArgumentException("Only an explicit Aiven review host is accepted");
        }
        int port = Integer.parseInt(required("SCS_M8_PORT"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid MySQL port");
        }
        String username = required("SCS_M8_USERNAME");
        String password = required("SCS_M8_PASSWORD");
        Path caPem = Path.of(required("SCS_M8_CA")).toRealPath();
        String scratch = "scs_m8_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        // Store only the PUBLIC CA in a one-use PKCS12 truststore. The MySQL
        // password stays in the process environment; no credentials go to disk.
        Path trustStore = Files.createTempFile("scs-m8-trust-", ".p12");
        byte[] secretBytes = new byte[24];
        new SecureRandom().nextBytes(secretBytes);
        String trustPass = HexFormat.of().formatHex(secretBytes);
        boolean created = false;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, trustPass.toCharArray());
            try (InputStream input = Files.newInputStream(caPem)) {
                ks.setCertificateEntry("aiven-project-ca",
                    CertificateFactory.getInstance("X.509").generateCertificate(input));
            }
            try (OutputStream output = Files.newOutputStream(trustStore)) {
                ks.store(output, trustPass.toCharArray());
            }

            Properties tls = new Properties();
            tls.setProperty("sslMode", "VERIFY_IDENTITY");
            tls.setProperty("trustCertificateKeyStoreUrl", trustStore.toUri().toString());
            tls.setProperty("trustCertificateKeyStoreType", "PKCS12");
            tls.setProperty("trustCertificateKeyStorePassword", trustPass);
            tls.setProperty("fallbackToSystemTrustStore", "false");
            tls.setProperty("allowPublicKeyRetrieval", "false");

            // Connect initially to Aiven's existing defaultdb, but NEVER
            // migrate or modify it. Only issue CREATE DATABASE for a newly
            // generated name after the remote server and TLS are validated.
            DriverManagerDataSource admin = datasource(host, port, "defaultdb", username, password, tls);
            try (Connection connection = admin.getConnection()) {
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT VERSION()")) {
                    assertTrue(result.next(), "MySQL server must respond");
                    assertTrue(result.getString(1).startsWith("8."),
                        "Remote host must run genuine MySQL 8.x");
                }
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                    assertTrue(result.next(), "TLS status must be present");
                    assertTrue(!result.getString(2).isBlank(), "TLS must be active");
                }

                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(
                        "CREATE DATABASE " + scratch
                            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                    created = true;
                }
            }

            DriverManagerDataSource scratchDataSource =
                datasource(host, port, scratch, username, password, tls);

            // Simulate a real upgrade, not a baseline/repair shortcut.
            Flyway first = Flyway.configure().dataSource(scratchDataSource)
                .locations("classpath:db/mysql")
                .target("14")
                .load();
            assertEquals(14, first.migrate().migrationsExecuted,
                "Exactly V1-V14 must migrate in the initial stage");

            Flyway current = Flyway.configure().dataSource(scratchDataSource)
                .locations("classpath:db/mysql")
                .load();
            assertEquals(17, current.migrate().migrationsExecuted,
                "Exactly V15-V31 must migrate on the same database");
            current.validate();
            assertEquals(0, current.migrate().migrationsExecuted,
                "A second migration must be idempotent");

            JdbcTemplate jdbc = new JdbcTemplate(scratchDataSource);
            assertEquals(31, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
            assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=? AND trigger_name='receipt_paid_guard'
                """, Integer.class, scratch));
            assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=? AND table_name='menu_items' AND column_name='image_url'
                """, Integer.class, scratch));
            System.out.println("PASS: hosted MySQL 8 Flyway V1-V14 then V15-V31; 31 validated migrations.");
        } finally {
            try {
                if (created) {
                    try (Connection connection = datasource(
                            host, port, "defaultdb", username, password,
                            trustProperties(trustStore, trustPass)).getConnection();
                         Statement statement = connection.createStatement()) {
                        statement.executeUpdate("DROP DATABASE " + scratch);
                        System.out.println("PASS: disposable Flyway database " + scratch + " removed.");
                    } catch (Exception cleanupFailure) {
                        System.err.println("CLEANUP FAILED: inspect ONLY database " + scratch
                            + " on the review service. Never drop any other schema.");
                        throw cleanupFailure;
                    }
                }
            } finally {
                Files.deleteIfExists(trustStore);
            }
        }
    }

    private static Properties trustProperties(Path trustStore, String trustPass) {
        Properties properties = new Properties();
        properties.setProperty("sslMode", "VERIFY_IDENTITY");
        properties.setProperty("trustCertificateKeyStoreUrl", trustStore.toUri().toString());
        properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
        properties.setProperty("trustCertificateKeyStorePassword", trustPass);
        properties.setProperty("fallbackToSystemTrustStore", "false");
        properties.setProperty("allowPublicKeyRetrieval", "false");
        return properties;
    }

    private static DriverManagerDataSource datasource(
            String host, int port, String schema, String username, String password, Properties tls) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + host + ":" + port + "/" + schema);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setConnectionProperties(tls);
        return ds;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required local setting " + key);
        }
        return value;
    }
}
