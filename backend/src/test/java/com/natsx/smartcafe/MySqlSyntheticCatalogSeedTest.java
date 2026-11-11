package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.natsx.smartcafe.catalog.MenuController;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.natsx.smartcafe.identity.StaffAuthService;
import java.util.UUID;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Actual MySQL 8.4 acceptance for the optional owner-local synthetic catalog.
 * No hosted Aiven secret, external database, account, payment or real order.
 */
@Testcontainers
class MySqlSyntheticCatalogSeedTest {
    @Container
    static final MySQLContainer mysql = new MySQLContainer(
        DockerImageName.parse("mysql:8.4"))
        .withCommand("--log-bin-trust-function-creators=1")
        .withDatabaseName("scs_disposable_seed_test")
        .withUsername("test")
        .withPassword("test-only-ephemeral");

    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrateFreshMySql() {
        dataSource = new DriverManagerDataSource(
            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/mysql").load();
        assertEquals(31, flyway.migrate().migrationsExecuted);
    }

    @Test
    void onlyAnEmptyReviewSchemaGetsAnAtomicVisibleSyntheticCatalog() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            SyntheticReviewCatalogSeed.verifyTargetsEmpty(connection);
            SyntheticReviewCatalogSeed.seed(connection);
            SyntheticReviewCatalogSeed.verifySeedCounts(connection);

            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var published = new MenuController(jdbc).menu(new MockHttpServletRequest()).data();
            assertEquals(12, published.size());
            assertTrue(published.stream().anyMatch(x ->
                "Spanish Latte".equals(x.name()) && x.priceIdr() == 32000L));

            assertThrows(IllegalStateException.class,
                () -> SyntheticReviewCatalogSeed.verifyTargetsEmpty(connection),
                "The seed must reject a schema containing any catalogue or table records.");
            // The exact same transaction can also roll back every seed row.
            connection.rollback();
        }
        try (Connection connection = dataSource.getConnection()) {
            SyntheticReviewCatalogSeed.verifyTargetsEmpty(connection);
            assertEquals(0L, count(connection, "cafe_tables"));
            assertEquals(0L, count(connection, "menu_items"));
            assertEquals(0L, count(connection, "physical_games"));
        }
    }

    @Test
    void seedRefusesImplicitAutoCommitToAvoidPartialData() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertThrows(IllegalStateException.class,
                () -> SyntheticReviewCatalogSeed.seed(connection));
            assertEquals(0L, count(connection, "menu_items"));
        }
    }


    @Test
    void firstSyntheticAdminCanLogInGetAdminRoleAndRevokeSessionOnMySql() throws Exception {
        // Test the real JDBC auth service on MySQL CHAR(36), not mocked
        // PostgreSQL-specific UUID behavior. All writes are rolled back.
        final String username = "reviewadmin-smoke";
        final String password = "Only-For-Disposable-MySQL-Test-9187";
        final String userId = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var encoder = new BCryptPasswordEncoder(12);
            jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
                userId, username, encoder.encode(password));
            jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,'ADMIN')",
                userId);
            var auth = new StaffAuthService(jdbc, encoder);

            var bad = auth.login(username, "not-the-correct-password");
            assertEquals("UNAUTHENTICATED", bad.failure());
            var good = auth.login(username.toUpperCase(java.util.Locale.ROOT), password);
            assertEquals(null, good.failure());
            assertTrue(good.token() != null && good.token().length() == 43);

            var principal = auth.authenticate(good.token());
            assertTrue(principal != null);
            assertEquals(UUID.fromString(userId), principal.staffId());
            assertTrue(principal.roles().contains("ADMIN"));
            assertEquals(1, principal.roles().size());

            auth.logout(principal);
            assertEquals(null, auth.authenticate(good.token()));
            connection.rollback();
        }
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0L, count(connection, "staff_users"));
            assertEquals(0L, count(connection, "staff_sessions"));
        }
    }

    private static long count(Connection connection, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
