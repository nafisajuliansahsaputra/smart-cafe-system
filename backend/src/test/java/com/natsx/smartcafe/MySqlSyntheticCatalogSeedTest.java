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
import com.natsx.smartcafe.identity.StaffPrincipal;
import com.natsx.smartcafe.fulfillment.WaiterTaskQueryService;
import com.natsx.smartcafe.ops.OrderControlQueryService;
import java.util.Set;
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


    @Test
    void waiterTaskProjectionAndRoleScopedUuidBindingsWorkOnMySql() throws Exception {
        // Exercise the real MySQL query (window function, date interval, JSON
        // extraction and ASCII UUID parameters), never a mocked row mapper.
        var waiterId = UUID.randomUUID();
        var tableId = UUID.randomUUID();
        var visitId = UUID.randomUUID();
        var guestId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
                waiterId.toString(), "waiter-port-review", "synthetic-unused-hash");
            jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
                tableId.toString(), "WREV01", "waiter-review-only", 4);
            jdbc.update("INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash) VALUES (?,?,?,?)",
                visitId.toString(), tableId.toString(), waiterId.toString(), "a".repeat(64));
            jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
                guestId.toString(), visitId.toString(), "Fictional Reviewer");
            jdbc.update("""
                INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,pricing_policy_version)
                VALUES (?,?,?,?,?,?,?)
                """, orderId.toString(),visitId.toString(),guestId.toString(),
                "SERVED",26000L,26000L,"synthetic-review");
            jdbc.update("""
                INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
                VALUES (?,?,?,?,?)
                """, intentId.toString(),orderId.toString(),"CASH",26000L,"AWAITING_CASH");
            jdbc.update("""
                INSERT INTO order_lines(order_id,name_snapshot,variant_snapshot,unit_price_idr,
                                        quantity,line_total_idr,notes)
                VALUES (?,?,?,?,?,?,?)
                """,orderId.toString(),"Iced Americano","{\"label\":\"Regular\"}",
                26000L,1,26000L,"Synthetic only");

            var service = new WaiterTaskQueryService(jdbc);
            var waiter = new StaffPrincipal(waiterId, "waiter-port-review", "synthetic",
                Set.of("WAITER"));
            var page = service.page(waiter, 0);
            assertEquals(1L, page.total());
            assertEquals(1, page.items().size());
            var task = page.items().getFirst();
            assertEquals(orderId, task.orderId());
            assertEquals(intentId, task.paymentIntentId());
            assertEquals("AWAITING_CASH", task.paymentStatus());
            assertEquals("Regular", task.lines().getFirst().variantLabel());
            assertEquals("Iced Americano", task.lines().getFirst().name());
            assertEquals(1, service.tasks(waiter).size());

            var stranger = new StaffPrincipal(UUID.randomUUID(), "other-waiter",
                "synthetic",Set.of("WAITER"));
            assertEquals(0L, service.page(stranger, 0).total());
            var admin = new StaffPrincipal(UUID.randomUUID(),"fake-supervisor","synthetic",
                Set.of("ADMIN"));
            assertEquals(1L, service.page(admin, 0).total());

            // Verify OCC performs parameterized CHAR(36) UUID filters and
            // reads JSON variant labels through MySQL's JSON_EXTRACT.
            var occ = new OrderControlQueryService(jdbc);
            var filtered = occ.page(0, "SERVED", "AWAITING_CASH", null, orderId);
            assertEquals(1L, filtered.total());
            assertEquals(orderId, filtered.items().getFirst().id());
            assertEquals(1, occ.search(null, null, null, orderId).size());
            var detail = occ.detail(orderId);
            assertEquals("AWAITING_CASH", detail.paymentStatus());
            assertEquals("Regular", detail.lines().getFirst().variantLabel());
            assertEquals("Iced Americano", detail.lines().getFirst().name());
            connection.rollback();
        }
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0L,count(connection,"orders"));
            assertEquals(0L,count(connection,"payment_intents"));
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
