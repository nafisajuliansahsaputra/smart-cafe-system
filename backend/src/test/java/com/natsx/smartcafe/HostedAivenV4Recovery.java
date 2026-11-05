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
import java.util.List;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * EXPLICIT LOCAL OPT-IN ONLY. Not run by default Maven/CI tests.
 * Repairs ONLY the exact known failed V4 partial-DDL state in an EMPTY
 * isolated scs_review_natsx schema. Never calls clean() or touches defaultdb.
 */
final class HostedAivenV4Recovery {
    private static final String SCHEMA = "scs_review_natsx";
    private static final String APPROVAL = "RECOVER_EMPTY_REVIEW_V4";

    @Test
    void recoverEmptyPartialV4AndMigrateTo31() throws Exception {
        if (!APPROVAL.equals(System.getenv("SCS_V4_APPROVAL"))) {
            throw new IllegalStateException("Explicit review V4 approval is required");
        }
        String host = required("SCS_V4_HOST");
        if (!host.matches("(?i)^[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.aivencloud\\.com$")) {
            throw new IllegalStateException("Only a genuine Aiven host is permitted");
        }
        int port = Integer.parseInt(required("SCS_V4_PORT"));
        if (port < 1 || port > 65535) throw new IllegalStateException("Invalid port");
        String username = required("SCS_V4_USERNAME");
        String password = required("SCS_V4_PASSWORD");
        Path publicCa = Path.of(required("SCS_V4_CA")).toRealPath();

        Path store = Files.createTempFile("scs-v4-trust-", ".p12");
        byte[] nonce = new byte[24];
        new SecureRandom().nextBytes(nonce);
        String storePassword = HexFormat.of().formatHex(nonce);
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, storePassword.toCharArray());
            try (InputStream pem = Files.newInputStream(publicCa)) {
                keyStore.setCertificateEntry("aiven-public-ca",
                    CertificateFactory.getInstance("X.509").generateCertificate(pem));
            }
            try (OutputStream output = Files.newOutputStream(store)) {
                keyStore.store(output, storePassword.toCharArray());
            }
            Properties tls = new Properties();
            tls.setProperty("sslMode", "VERIFY_IDENTITY");
            tls.setProperty("trustCertificateKeyStoreUrl", store.toUri().toString());
            tls.setProperty("trustCertificateKeyStoreType", "PKCS12");
            tls.setProperty("trustCertificateKeyStorePassword", storePassword);
            tls.setProperty("fallbackToSystemTrustStore", "false");
            tls.setProperty("allowPublicKeyRetrieval", "false");

            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            ds.setUrl("jdbc:mysql://" + host + ":" + port + "/" + SCHEMA);
            ds.setUsername(username);
            ds.setPassword(password);
            ds.setConnectionProperties(tls);
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            try (Connection c = ds.getConnection();
                 Statement stmt = c.createStatement();
                 ResultSet tlsStatus = stmt.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                assertTrue(tlsStatus.next() && !tlsStatus.getString(2).isBlank(),
                    "TLS must be active before any review operations");
                assertEquals(SCHEMA, c.getCatalog(),
                    "Refusing to operate on any other database");
            }

            List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=? AND table_type='BASE TABLE'
                  AND table_name NOT IN ('staff_roles','flyway_schema_history')
                ORDER BY table_name
                """, String.class, SCHEMA);
            assertTrue(tables.size() == 18 || tables.size() == 19,
                "Unexpected review table inventory; manual investigation required");
            for (String table : tables) {
                if (!table.matches("[a-z][a-z0-9_]*")) {
                    throw new IllegalStateException("Unsafe identifier in review schema");
                }
                // The identifier is checked against an allowlisted regex;
                // no customer input or untrusted SQL fragments reach here.
                assertEquals(0L,
                    jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                    "Review table is not empty: " + table);
            }

            List<Migration> history = jdbc.query("""
                SELECT version,success FROM flyway_schema_history
                ORDER BY installed_rank
                """, (rs,n) -> new Migration(rs.getString(1), rs.getBoolean(2)));
            if (history.size() == 31 && history.stream().allMatch(Migration::success)) {
                Flyway alreadyMigrated = Flyway.configure().dataSource(ds)
                    .locations("classpath:db/mysql").load();
                alreadyMigrated.validate();
                System.out.println("PASS: review database already has 31 validated migrations.");
                return;
            }
            assertEquals(4, history.size(), "Only V1-V3 and failed V4 may exist");
            for (int i = 0; i < 3; i++) {
                assertEquals(Integer.toString(i+1), history.get(i).version());
                assertTrue(history.get(i).success(), "V1-V3 must be successful");
            }
            assertEquals("4", history.get(3).version());
            assertTrue(!history.get(3).success(), "V4 must be the only failure");

            long receipts = count(jdbc, """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=? AND table_name='receipts'
                """);
            long updateTrigger = trigger(jdbc, "receipt_no_update");
            long deleteTrigger = trigger(jdbc, "receipt_no_delete");
            long snapshotTrigger = trigger(jdbc, "order_snapshot_no_update");
            assertEquals(0L, snapshotTrigger, "Unexpected financial snapshot trigger");
            boolean knownPartial = receipts==1 && updateTrigger==1 && deleteTrigger==1;
            boolean alreadyRemoved = receipts==0 && updateTrigger==0 && deleteTrigger==0;
            assertTrue(knownPartial || alreadyRemoved,
                "Unknown partial migration state; no repair attempted");

            System.out.println("PASS: all review business tables empty; exact failed V4 verified.");
            if (knownPartial) {
                jdbc.execute("DROP TRIGGER receipt_no_update");
                jdbc.execute("DROP TRIGGER receipt_no_delete");
                jdbc.execute("DROP TABLE receipts");
            }
            Flyway flyway = Flyway.configure().dataSource(ds)
                .locations("classpath:db/mysql").load();
            flyway.repair();
            assertEquals(3L,
                jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Long.class));
            System.out.println("PASS: removed only partial V4 artifacts; Flyway repair completed.");
            flyway.migrate();
            flyway.validate();
            assertEquals(31L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE success=1
                """, Long.class));
            assertEquals(1L, trigger(jdbc, "receipt_paid_guard"));
            assertEquals(1L, trigger(jdbc, "order_snapshot_no_update"));
            System.out.println("PASS: all 31 hosted MySQL migrations validated, no business data imported.");
        } finally {
            Files.deleteIfExists(store);
        }
    }

    private static long trigger(JdbcTemplate jdbc,String name) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.triggers
            WHERE trigger_schema=? AND trigger_name=?
            """,Long.class,SCHEMA,name);
    }

    private static long count(JdbcTemplate jdbc,String sql) {
        return jdbc.queryForObject(sql,Long.class,SCHEMA);
    }

    private static String required(String key) {
        String val=System.getenv(key);
        if (val==null || val.isBlank()) {
            throw new IllegalStateException("Missing local-only configuration " + key);
        }
        return val;
    }

    private record Migration(String version,boolean success) {}
}
