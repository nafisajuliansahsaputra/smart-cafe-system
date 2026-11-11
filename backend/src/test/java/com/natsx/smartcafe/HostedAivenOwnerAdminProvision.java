package com.natsx.smartcafe;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * OWNER-LOCAL one-time provision of the FIRST synthetic Aiven review ADMIN.
 * Deliberately does not match Surefire's default *Test.java pattern.
 *
 * Explicit runner only: scripts/provision-aiven-review-admin-windows.ps1.
 * No hosted container startup profile, schema mutations, default credentials,
 * or production database access. All writes share one InnoDB transaction.
 */
final class HostedAivenOwnerAdminProvision {
    private static final String DATABASE = "scs_review_natsx";
    private static final String AUTHORIZATION = "CREATE_ONE_SYNTHETIC_AIVEN_ADMIN";

    @Test
    void createFirstOwnerOnlyWhenReviewSchemaIsCompleteAndEmpty() throws Exception {
        if (!AUTHORIZATION.equals(System.getenv("SCS_OWNER_PROVISION_AUTHORIZATION"))) {
            throw new IllegalStateException("Explicit local ADMIN provisioning is required");
        }
        String host = required("SCS_OWNER_AIVEN_HOST");
        if (!host.matches("(?i)^mysql-[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.aivencloud\\.com$")) {
            throw new IllegalStateException("Refusing non-Aiven database host");
        }
        int port = Integer.parseInt(required("SCS_OWNER_AIVEN_PORT"));
        if (port < 1 || port > 65535) throw new IllegalStateException("Invalid database port");
        String dbUsername = required("SCS_OWNER_AIVEN_USERNAME");
        String dbPassword = required("SCS_OWNER_AIVEN_PASSWORD");
        String adminUsername = required("SCS_OWNER_ADMIN_USERNAME");
        String adminPassword = required("SCS_OWNER_ADMIN_PASSWORD");
        if (!adminUsername.matches("[A-Za-z][A-Za-z0-9._-]{2,39}")
                || adminPassword.length() < 24 || adminPassword.length() > 128) {
            throw new IllegalStateException("Review ADMIN credentials failed the safety policy");
        }
        adminUsername = adminUsername.toLowerCase(Locale.ROOT);
        final boolean seedCatalog = "true".equals(System.getenv("SCS_OWNER_SEED_CATALOG"));

        Path caPath = Path.of(required("SCS_OWNER_AIVEN_CA")).toRealPath();
        Path truststore = Files.createTempFile("scs-owner-review-", ".p12");
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String keyStorePassword = HexFormat.of().formatHex(random);
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, keyStorePassword.toCharArray());
            try (InputStream input = Files.newInputStream(caPath)) {
                X509Certificate certificate = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(input);
                certificate.checkValidity();
                store.setCertificateEntry("aiven-review-ca", certificate);
            }
            try (OutputStream output = Files.newOutputStream(truststore)) {
                store.store(output, keyStorePassword.toCharArray());
            }
            Properties ssl = new Properties();
            ssl.setProperty("sslMode", "VERIFY_IDENTITY");
            ssl.setProperty("trustCertificateKeyStoreUrl", truststore.toUri().toString());
            ssl.setProperty("trustCertificateKeyStoreType", "PKCS12");
            ssl.setProperty("trustCertificateKeyStorePassword", keyStorePassword);
            ssl.setProperty("fallbackToSystemTrustStore", "false");
            ssl.setProperty("allowPublicKeyRetrieval", "false");
            var dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl("jdbc:mysql://" + host + ":" + port + "/" + DATABASE);
            dataSource.setUsername(dbUsername);
            dataSource.setPassword(dbPassword);
            dataSource.setConnectionProperties(ssl);

            // Never repair, clean, migrate or initialize MySQL schema at this step.
            Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/mysql").load();
            MigrationInfo[] applied = flyway.info().applied();
            if (applied.length != 31 || flyway.info().pending().length != 0) {
                throw new IllegalStateException("Review schema must be fully migrated through V31");
            }
            for (MigrationInfo migration : applied) {
                if (migration.getState() != MigrationState.SUCCESS) {
                    throw new IllegalStateException("Incomplete migration; refusing ADMIN provision");
                }
            }
            flyway.validate();

            try (Connection connection = dataSource.getConnection()) {
                if (!DATABASE.equals(connection.getCatalog())) {
                    throw new IllegalStateException("Unexpected database catalog");
                }
                try (PreparedStatement checkTls = connection.prepareStatement(
                        "SHOW SESSION STATUS LIKE 'Ssl_cipher'");
                     ResultSet rows = checkTls.executeQuery()) {
                    if (!rows.next() || rows.getString(2) == null
                            || rows.getString(2).isBlank()) {
                        throw new IllegalStateException("Verified MySQL TLS is mandatory");
                    }
                }

                // The permanent ADMIN role row is a transaction-owned lock.
                // Concurrent local provisioners serialize, then recheck users.
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement lock = connection.prepareStatement(
                            "SELECT role_key FROM staff_roles WHERE role_key='ADMIN' FOR UPDATE");
                         ResultSet rows = lock.executeQuery()) {
                        if (!rows.next() || !"ADMIN".equals(rows.getString(1))
                                || rows.next()) {
                            throw new IllegalStateException("Migrated ADMIN role is missing");
                        }
                    }
                    try (PreparedStatement count = connection.prepareStatement(
                            "SELECT COUNT(*) FROM staff_users");
                         ResultSet rows = count.executeQuery()) {
                        if (!rows.next() || rows.getLong(1) != 0L) {
                            throw new IllegalStateException(
                                "Staff already exists; will never overwrite or create a second ADMIN");
                        }
                    }

                    if (seedCatalog) {
                        SyntheticReviewCatalogSeed.verifyTargetsEmpty(connection);
                    }
                    String staffId = UUID.randomUUID().toString();
                    String passwordHash = new BCryptPasswordEncoder(12).encode(adminPassword);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)")) {
                        insert.setString(1, staffId);
                        insert.setString(2, adminUsername);
                        insert.setString(3, passwordHash);
                        if (insert.executeUpdate() != 1) {
                            throw new IllegalStateException("Exactly one staff row must be inserted");
                        }
                    }
                    try (PreparedStatement assign = connection.prepareStatement(
                            "INSERT INTO staff_role_assignments(staff_user_id,role_key) "
                            + "VALUES (?,'ADMIN')")) {
                        assign.setString(1, staffId);
                        if (assign.executeUpdate() != 1) {
                            throw new IllegalStateException("Exactly one ADMIN assignment must be inserted");
                        }
                    }
                    if (seedCatalog) {
                        SyntheticReviewCatalogSeed.seed(connection);
                    }
                    connection.commit();
                } catch (Exception failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                        // Preserve original exception; no retries against a live database.
                    }
                    throw failure;
                }
            }
            System.out.println("PASS: exactly one review ADMIN provisioned transactionally.");
            if (seedCatalog) {
                System.out.println("PASS: six tables, 12 menu items, 4 categories, 2 games, 3 copies seeded atomically.");
            }
            System.out.println("No migrations, schema resets, checkout or real payments were performed.");
        } finally {
            Files.deleteIfExists(truststore);
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing locally supplied " + key);
        }
        return value;
    }
}
