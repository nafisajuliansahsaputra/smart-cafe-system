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
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Explicit one-time, OWNER-LOCAL migration of the *existing*, synthetic-only
 * Aiven MySQL review database. Not picked up by normal Maven test discovery.
 *
 * Fail closed unless manually invoked by scripts/migrate-aiven-review-windows.ps1.
 * Never use defaultdb, real cafe data, prod credentials or a MySQL live cutover.
 */
final class HostedAivenPersistentReview {
    private static final String DATABASE = "scs_review_natsx";
    private static final Set<String> STANDARD_ROLES =
        Set.of("ADMIN", "MANAGER", "WAITER", "KITCHEN", "CASHIER");

    @Test
    void recoverOnlyEmptyReviewSchemaAndMigrateV1ToV31() throws Exception {
        if (!"RESET_EMPTY_SYNTHETIC_REVIEW_ONLY".equals(
                System.getenv("SCS_REVIEW_MIGRATION_AUTHORIZATION"))) {
            throw new IllegalStateException(
                "Persistent review migration requires deliberate local authorization");
        }
        String host = required("SCS_REVIEW_HOST");
        if (!host.matches("(?i)^[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.aivencloud\\.com$")) {
            throw new IllegalArgumentException("Refusing non-Aiven review host");
        }
        int port = Integer.parseInt(required("SCS_REVIEW_PORT"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        String username = required("SCS_REVIEW_USERNAME");
        String password = required("SCS_REVIEW_PASSWORD");
        Path certificate = Path.of(required("SCS_REVIEW_CA")).toRealPath();
        Path trustStore = Files.createTempFile("scs-aiven-review-", ".p12");
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String storePass = HexFormat.of().formatHex(random);
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(null, storePass.toCharArray());
            try (InputStream ca = Files.newInputStream(certificate)) {
                keystore.setCertificateEntry("aiven-project-ca",
                    CertificateFactory.getInstance("X.509").generateCertificate(ca));
            }
            try (OutputStream output = Files.newOutputStream(trustStore)) {
                keystore.store(output, storePass.toCharArray());
            }
            Properties tls = new Properties();
            tls.setProperty("sslMode", "VERIFY_IDENTITY");
            tls.setProperty("trustCertificateKeyStoreUrl", trustStore.toUri().toString());
            tls.setProperty("trustCertificateKeyStoreType", "PKCS12");
            tls.setProperty("trustCertificateKeyStorePassword", storePass);
            tls.setProperty("fallbackToSystemTrustStore", "false");
            tls.setProperty("allowPublicKeyRetrieval", "false");

            // The administrative connection is read-only, except for Flyway
            // clean/migrate on exactly DATABASE after the empty-data gate.
            var admin = datasource(host,port,"defaultdb",username,password,tls);
            JdbcTemplate meta = new JdbcTemplate(admin);
            Integer exists = meta.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,DATABASE);
            assertEquals(1,exists,"The isolated review database must exist in advance");
            try (Connection connection = admin.getConnection();
                 Statement stmt = connection.createStatement();
                 ResultSet ssl = stmt.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                assertTrue(ssl.next() && !ssl.getString(2).isBlank(),
                    "Aiven must use a verified TLS connection");
            }

            var target = datasource(host,port,DATABASE,username,password,tls);
            JdbcTemplate jdbc = new JdbcTemplate(target);
            boolean history = existsTable(meta,"flyway_schema_history");

            Flyway flyway=Flyway.configure()
                .dataSource(target).locations("classpath:db/mysql").load();

            if (history) {
                int applied = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",
                    Integer.class);
                int failed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0",
                    Integer.class);
                if (applied==31 && failed==0) {
                    flyway.validate();
                    assertEquals(0,flyway.migrate().migrationsExecuted);
                    verifyCore(jdbc);
                    System.out.println("PASS: persistent review already has all 31 valid migrations; no reset performed.");
                    return;
                }
                System.out.println("Review schema incomplete (success="+applied+", failed="+failed+").");
            }

            // This is intentionally the last and strongest authorization
            // gate before clean(): any business row, including synthetic
            // sessions, menus or staff, must stop the reset. Flyway history
            // and five immutable migration-generated roles are exempt.
            List<String> tables=meta.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=? AND table_type='BASE TABLE'
                ORDER BY table_name
                """,String.class,DATABASE);
            for (String table:tables) {
                if (!table.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) {
                    throw new IllegalStateException("Unexpected table identifier; no cleanup performed");
                }
                if ("flyway_schema_history".equals(table)) continue;
                if ("staff_roles".equals(table)) {
                    var roles=jdbc.queryForList("SELECT role_key FROM staff_roles",String.class);
                    if (!STANDARD_ROLES.equals(Set.copyOf(roles)) || roles.size()!=5) {
                        throw new IllegalStateException("Review roles were modified; refusing destructive cleanup");
                    }
                    continue;
                }
                Long rows=jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Long.class);
                if (rows!=null && rows>0) {
                    throw new IllegalStateException(
                        "Review contains records in "+table+"; refusing to reset or delete any data");
                }
            }

            // MySQL DDL is non-transactional. An interrupted cold start can
            // leave V4 objects while schema history marks migration failed.
            // Flyway clean on the pre-checked EMPTY synthetic schema is safer
            // than repair()+blind DROP statements. This target is fixed.
            if (!tables.isEmpty()) {
                System.out.println("Cleaning ONLY the empty, incomplete synthetic "+DATABASE+" schema.");
                Flyway.configure().dataSource(target).locations("classpath:db/mysql")
                    .cleanDisabled(false).load().clean();
            }
            Flyway migrated=Flyway.configure().dataSource(target)
                .locations("classpath:db/mysql").load();
            assertEquals(31,migrated.migrate().migrationsExecuted,
                "The empty synthetic review must migrate V1 through V31");
            migrated.validate();
            assertEquals(0,migrated.migrate().migrationsExecuted,
                "A second migration must be idempotent");
            verifyCore(jdbc);
            System.out.println("PASS: isolated Aiven scs_review_natsx V1-V31 verified. No schema was dropped after success.");
        } finally {
            Files.deleteIfExists(trustStore);
        }
    }

    private static boolean existsTable(JdbcTemplate meta,String table) {
        Integer n=meta.queryForObject("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema=? AND table_name=?
            """,Integer.class,DATABASE,table);
        return n!=null && n==1;
    }

    private static void verifyCore(JdbcTemplate jdbc) {
        assertEquals(31,jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
        Integer trigger=jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.triggers
            WHERE trigger_schema=? AND trigger_name='receipt_paid_guard'
            """,Integer.class,DATABASE);
        assertEquals(1,trigger,"Paid-only receipt trigger must be present");
        Integer imageUrl=jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=? AND table_name='menu_items' AND column_name='image_url'
            """,Integer.class,DATABASE);
        assertEquals(1,imageUrl);
    }

    private static DriverManagerDataSource datasource(String host,int port,String database,
            String username,String password,Properties tls) {
        var ds=new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://"+host+":"+port+"/"+database);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setConnectionProperties(tls);
        return ds;
    }

    private static String required(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) {
            throw new IllegalStateException("Missing locally supplied "+name);
        }
        return value;
    }
}
