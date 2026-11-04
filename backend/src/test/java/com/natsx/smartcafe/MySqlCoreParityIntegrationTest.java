package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Isolated MySQL core-schema parity tests; never shares a PG service/database.
 * The MySQL runtime remains fail-closed until ALL 31 migrations are ported.
 */
@Testcontainers
class MySqlCoreParityIntegrationTest {
    @Container
    static final MySQLContainer mysql =
        new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            // MySQL binlog requires SUPER or trusted-function-creators for
            // CREATE TRIGGER. Docker CI can set this explicitly; managed
            // hosts MUST prove equivalent capability before any deployment.
            .withCommand("--log-bin-trust-function-creators=1")
            .withDatabaseName("smart_cafe_mysql_test")
            .withUsername("cafe_test")
            .withPassword("test-only-local-mysql-pass");

    static JdbcTemplate jdbc;
    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        // A real staged upgrade, not a Flyway baseline shortcut:
        // first deploy V1-V14, then migrate that same disposable database
        // to V31. Both stages execute all scripts, retaining history.
        // This covers a fresh database and a forward-only upgrade path.
        var initial = Flyway.configure().dataSource(ds)
            .locations("classpath:db/mysql")
            .target("14")
            .load();
        assertEquals(14, initial.migrate().migrationsExecuted);

        var upgraded = Flyway.configure().dataSource(ds)
            .locations("classpath:db/mysql")
            .load();
        assertEquals(17, upgraded.migrate().migrationsExecuted);
        // Applying the current head a second time must be a no-op.
        assertEquals(0, upgraded.migrate().migrationsExecuted);
        jdbc = new JdbcTemplate(ds);
        dataSource = ds;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String newStaff() {
        String user = id();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            user, ("test-" + user).substring(0, 40), "dummy-hash-not-for-login");
        return user;
    }

    private static String newTable() {
        String table = id();
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table, "T" + table.substring(0, 8), "mysql-" + table, 4);
        return table;
    }

    private static String newVisit(String table, String staff) {
        String visit = id();
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """, visit, table, staff, "a".repeat(64));
        return visit;
    }

    @Test
    void activeTableConstraintIsEquivalentToPostgresPartialUnique() {
        String staff = newStaff();
        String table = newTable();
        String first = newVisit(table, staff);
        assertThrows(DataAccessException.class, () -> newVisit(table, staff));
        jdbc.update("""
            UPDATE table_visits SET status='CLOSED',closed_at=CURRENT_TIMESTAMP(6)
            WHERE id=?
            """, first);
        newVisit(table, staff);
    }

    @Test
    void staffLoginUsesMySqlUuidSessionsRevokesAndPersistsFailedThrottles() {
        var passwords = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        var auth = new com.natsx.smartcafe.identity.StaffAuthService(jdbc, passwords);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        String staffId = newStaff();
        String login = jdbc.queryForObject(
            "SELECT login FROM staff_users WHERE id=?", String.class, staffId);
        jdbc.update("UPDATE staff_users SET password_hash=? WHERE id=?",
            passwords.encode("test-staff-secret-24-bytes"), staffId);
        jdbc.update(
            "INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            staffId, "WAITER");

        var signedIn = tx.execute(status -> auth.login(login, "test-staff-secret-24-bytes"));
        assertEquals(null, signedIn.failure());
        var principal = tx.execute(status -> auth.authenticate(signedIn.token()));
        assertEquals(UUID.fromString(staffId), principal.staffId());
        assertEquals(true, principal.roles().contains("WAITER"));
        tx.executeWithoutResult(status -> auth.logout(principal));
        org.junit.jupiter.api.Assertions.assertNull(
            tx.execute(status -> auth.authenticate(signedIn.token())));

        // Failed passwords are recorded on a separate absent identifier.
        // Returning a denial must commit the throttle counter.
        String absent = "missing-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            assertEquals("UNAUTHENTICATED",
                tx.execute(status -> auth.login(absent, "bad-password")).failure());
        }
        assertEquals("RATE_LIMITED",
            tx.execute(status -> auth.login(absent, "bad-password")).failure());
        assertEquals(5, jdbc.queryForObject(
            "SELECT failed_attempts FROM staff_login_throttles WHERE login_hash=?",
            Integer.class, com.natsx.smartcafe.identity.SecureToken.sha256(absent)));
    }

    @Test
    void guestJoinThrottleRejectsTheTwentyFifthAttemptWithinSameTenMinuteWindow() {
        // Exercise the real service's three HMAC buckets and transaction
        // boundary, not merely the vendor SQL chosen by a unit test.
        var limiter = new com.natsx.smartcafe.tables.GuestJoinThrottle(
            jdbc, "synthetic-test-secret-32-plus-bytes-never-production");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        String alias = "throttle-" + UUID.randomUUID();
        String client = "192.0.2.40";

        for (int i = 0; i < 24; i++) {
            assertEquals(Boolean.TRUE, tx.execute(status -> limiter.allow(alias, client)));
        }
        assertEquals(Boolean.FALSE, tx.execute(status -> limiter.allow(alias, client)));

        // Only hashed buckets may be stored. The three rows should represent
        // peer, table and pair, with no plain IP address or QR alias.
        assertEquals(3, jdbc.queryForObject(
            "SELECT COUNT(*) FROM guest_join_throttles WHERE attempts > 0",
            Integer.class));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM guest_join_throttles WHERE bucket_hash LIKE ?",
            Integer.class, "%192.0.2.40%"));
    }

    @Test
    void guestJoinThrottleMySqlIncrementsResetsExpiredWindowAndPurges() {
        var dialect = com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL;
        var statement = com.natsx.smartcafe.common.db.JdbcSqlDialect.consumeGuestJoinThrottle(dialect);
        String key = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "");

        jdbc.update(statement, key);
        assertEquals(1, jdbc.queryForObject(
            "SELECT attempts FROM guest_join_throttles WHERE bucket_hash=?",
            Integer.class, key));
        jdbc.update(statement, key);
        assertEquals(2, jdbc.queryForObject(
            "SELECT attempts FROM guest_join_throttles WHERE bucket_hash=?",
            Integer.class, key));

        // Simulate an expired fixed-window bucket. A new attempt must
        // reset to one rather than carrying a stale lockout indefinitely.
        jdbc.update("""
            UPDATE guest_join_throttles
            SET window_started_at=NOW(6)-INTERVAL 11 MINUTE
            WHERE bucket_hash=?
            """, key);
        jdbc.update(statement, key);
        assertEquals(1, jdbc.queryForObject(
            "SELECT attempts FROM guest_join_throttles WHERE bucket_hash=?",
            Integer.class, key));

        jdbc.update("""
            UPDATE guest_join_throttles
            SET updated_at=NOW(6)-INTERVAL 3 DAY WHERE bucket_hash=?
            """, key);
        jdbc.update(com.natsx.smartcafe.common.db.JdbcSqlDialect.purgeGuestJoinThrottle(dialect));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM guest_join_throttles WHERE bucket_hash=?",
            Integer.class, key));
    }

    @Test
    void mySqlGuestJoinUsesSharedLockOnVisitAliasWithoutLockingPublicTable() {
        String staff = newStaff();
        String table = newTable();
        String visit = newVisit(table, staff);

        String visitId = jdbc.queryForObject("""
            SELECT v.id
            FROM cafe_tables t JOIN table_visits v ON v.table_id=t.id
            WHERE t.public_alias=? AND v.status='ACTIVE'
            FOR SHARE OF v
            """, (rs, row) -> com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id").toString(),
            "mysql-" + table);

        assertEquals(visit, visitId);
    }

    @Test
    void mySqlVisitForeignKeyReadsUuidThroughDialectAdapter() {
        String staff = newStaff();
        String table = newTable();
        String visit = newVisit(table, staff);
        UUID fromDb = jdbc.queryForObject(
            "SELECT table_id FROM table_visits WHERE id=?",
            (rs, row) -> com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "table_id"),
            com.natsx.smartcafe.common.db.JdbcUuid.parameter(
                UUID.fromString(visit),
                com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc))
        );
        assertEquals(UUID.fromString(table), fromDb);
    }

    @Test
    void receiptRequiresPaidExactAmountAndEvidenceIsImmutable() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Fictional guest");
        String order = id();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,?,?,?)
            """, order, visit, guest, 50000L, 50000L, "test-v1");
        String intent = id();
        jdbc.update("""
            INSERT INTO payment_intents(
                id,order_id,method_selected,amount_due_idr,amount_paid_idr,aggregate_status)
            VALUES (?,?,?,?,?,?)
            """, intent, order, "CASH", 50000L, 0L, "AWAITING_CASH");
        String receipt = id();

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            VALUES (?,?,?,?,?)
            """, receipt, intent, order, 50000L, "{}"));

        jdbc.update("""
            UPDATE payment_intents SET amount_paid_idr=50000,aggregate_status='PAID'
            WHERE id=?
            """, intent);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            VALUES (?,?,?,?,?)
            """, receipt, intent, order, 49999L, "{}"));
        jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            VALUES (?,?,?,?,?)
            """, receipt, intent, order, 50000L, "{}");
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipts WHERE id=?", Integer.class, receipt));

        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE receipts SET total_idr=100 WHERE id=?", receipt));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("DELETE FROM receipts WHERE id=?", receipt));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE orders SET total_idr=100 WHERE id=?", order));
    }

    @Test
    void menuStockTriggerAndVariantGroupingWorkUnderMySqlConstraints() {
        String category = id();
        String item = id();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category, "Test category", "mysql-" + category);
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr)
            VALUES (?,?,?,?)
            """, item, category, "Synthetic item", 25000L);
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM item_availability WHERE menu_item_id=?",
            Integer.class, item));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE item_availability SET limited_quantity=-1 WHERE menu_item_id=?",
                item));

        String groupOne = id();
        String groupTwo = id();
        jdbc.update("""
            INSERT INTO menu_variant_groups(id,menu_item_id,name,required)
            VALUES (?,?,?,?)
            """, groupOne, item, "Size", true);
        jdbc.update("""
            INSERT INTO menu_variant_groups(id,menu_item_id,name,required)
            VALUES (?,?,?,?)
            """, groupTwo, item, "Milk", false);
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM menu_variant_groups WHERE menu_item_id=?",
            Integer.class, item));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO menu_variant_groups(id,menu_item_id,name,required)
                VALUES (?,?,?,?)
                """, id(), item, "size", true));
    }

    @Test
    void serializedGameCopiesCannotBeDoubleLoanedAndVisitRequestsAreUnique() {
        String staff = newStaff();
        String table1 = newTable();
        String table2 = newTable();
        String visit1 = newVisit(table1, staff);
        String visit2 = newVisit(table2, staff);
        String guest1 = id();
        String guest2 = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest1, visit1, "Synthetic guest A");
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest2, visit2, "Synthetic guest B");
        String game = id();
        String copy = id();
        jdbc.update("INSERT INTO physical_games(id,name) VALUES (?,?)",
            game, "Synthetic game " + game);
        jdbc.update("INSERT INTO physical_game_copies(id,game_id,copy_code) VALUES (?,?,?)",
            copy, game, "CP-" + copy);

        String waiting = id();
        jdbc.update("""
            INSERT INTO physical_game_loans(id,visit_id,guest_id,game_id)
            VALUES (?,?,?,?)
            """, waiting, visit1, guest1, game);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO physical_game_loans(id,visit_id,guest_id,game_id)
            VALUES (?,?,?,?)
            """, id(), visit1, guest1, game));
        jdbc.update("UPDATE physical_game_loans SET status='CANCELLED' WHERE id=?", waiting);

        String assigned = id();
        jdbc.update("""
            INSERT INTO physical_game_loans(
                id,visit_id,guest_id,game_id,copy_id,status,assigned_at)
            VALUES (?,?,?,?,?,'ASSIGNED',CURRENT_TIMESTAMP(6))
            """, assigned, visit1, guest1, game, copy);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO physical_game_loans(
                id,visit_id,guest_id,game_id,copy_id,status,assigned_at)
            VALUES (?,?,?,?,?,'ASSIGNED',CURRENT_TIMESTAMP(6))
            """, id(), visit2, guest2, game, copy));
        jdbc.update("""
            UPDATE physical_game_loans
            SET status='RETURNED',
                handed_over_at=CURRENT_TIMESTAMP(6),
                returned_at=CURRENT_TIMESTAMP(6)
            WHERE id=?
            """, assigned);
        jdbc.update("""
            INSERT INTO physical_game_loans(
                id,visit_id,guest_id,game_id,copy_id,status,assigned_at)
            VALUES (?,?,?,?,?,'ASSIGNED',CURRENT_TIMESTAMP(6))
            """, id(), visit2, guest2, game, copy);
    }

    @Test
    void mysqlUuidDialectParameterAndReadAdapterRoundTrip() {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO staff_users(id,login,password_hash)
            VALUES (?,?,?)
            """, com.natsx.smartcafe.common.db.JdbcUuid.parameter(
                userId, com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)),
            "uuid-" + userId, "synthetic-test-hash");
        UUID fromDb = jdbc.queryForObject("""
            SELECT id FROM staff_users WHERE id=?
            """, (rs, n) -> com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
            userId.toString());
        assertEquals(userId, fromDb);
    }

    @Test
    void atMostOneActiveCashShiftAndClosedShiftIsImmutable() {
        String staff = newStaff();
        String first = id();
        jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,?,?)
            """, first, staff, 20000L, "open-" + first);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,?,?)
            """, id(), staff, 30000L, "open-" + id()));

        jdbc.update("""
            UPDATE cash_drawer_shifts
            SET closed_at=CURRENT_TIMESTAMP(6),closed_by=?,close_key=?,
                counted_cash_idr=20000,recorded_cash_idr=0,
                expected_drawer_idr=20000,difference_idr=0,closing_reason='test close'
            WHERE id=?
            """, staff, "close-" + first, first);
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE cash_drawer_shifts SET closing_reason='changed' WHERE id=?", first));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("DELETE FROM cash_drawer_shifts WHERE id=?", first));
        jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,?,?)
            """, id(), staff, 30000L, "open-" + id());
    }

    @Test
    void fakeDigitalAttemptsCannotAttachToCashOrCreateDuplicatePendingCharges() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic guest");
        String order = id();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,?,?,?)
            """, order, visit, guest, 35000L, 35000L, "test-v1");
        String intent = id();
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,?,?,?)
            """, intent, order, "CASH", 35000L, "AWAITING_CASH");

        String fakeRef = "FAKE_" + id().replace("-", "");
        String firstAttempt = id();
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO digital_payment_attempts(
                id,payment_intent_id,method,provider_key,provider_reference,
                idempotency_key,amount_idr,expires_at)
            VALUES (?,?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 10 MINUTE))
            """, firstAttempt, intent, "QRIS", "SANDBOX_FAKE", fakeRef,
            "sandbox_fake_key_00001", 35000L));

        jdbc.update("UPDATE payment_intents SET method_selected='QRIS',aggregate_status='PENDING' WHERE id=?",
            intent);
        jdbc.update("""
            INSERT INTO digital_payment_attempts(
                id,payment_intent_id,method,provider_key,provider_reference,
                idempotency_key,amount_idr,expires_at)
            VALUES (?,?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 10 MINUTE))
            """, firstAttempt, intent, "QRIS", "SANDBOX_FAKE", fakeRef,
            "sandbox_fake_key_00001", 35000L);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO digital_payment_attempts(
                id,payment_intent_id,method,provider_key,provider_reference,
                idempotency_key,amount_idr,expires_at)
            VALUES (?,?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 10 MINUTE))
            """, id(), intent, "QRIS", "SANDBOX_FAKE",
            "FAKE_" + id().replace("-", ""), "sandbox_fake_key_00002", 35000L));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE digital_payment_attempts SET state='SUCCEEDED' WHERE id=?",
                firstAttempt));
        jdbc.update("UPDATE digital_payment_attempts SET state='PENDING' WHERE id=?",
            firstAttempt);
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE digital_payment_attempts SET amount_idr=5 WHERE id=?",
                firstAttempt));
    }

    @Test
    void mysqlThrottleAndCartIdempotencyPreserveExistingValues() {
        var vendor = com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        assertEquals(com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL, vendor);
        String throttleSql =
            com.natsx.smartcafe.common.db.JdbcSqlDialect.ensureStaffLoginThrottle(vendor);
        String loginHash = "a".repeat(64);
        jdbc.update(throttleSql, loginHash);
        jdbc.update("UPDATE staff_login_throttles SET failed_attempts=4 WHERE login_hash=?",
            loginHash);
        jdbc.update(throttleSql, loginHash);
        assertEquals(4, jdbc.queryForObject(
            "SELECT failed_attempts FROM staff_login_throttles WHERE login_hash=?",
            Integer.class, loginHash));

        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic cart owner");
        String cartSql =
            com.natsx.smartcafe.common.db.JdbcSqlDialect.ensureGuestCart(vendor);
        jdbc.update(cartSql, guest, visit);
        jdbc.update("UPDATE carts SET version=7 WHERE guest_id=?", guest);
        jdbc.update(cartSql, guest, visit);
        assertEquals(7L, jdbc.queryForObject(
            "SELECT version FROM carts WHERE guest_id=?", Long.class, guest));
    }

    @Test
    void financialAndPhysicalStateEnumerationsRejectIncorrectCase() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic guest");
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE table_visits SET status='active' WHERE id=?", visit));
        String order = id();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,?,?,?)
            """,order,visit,guest,10000L,10000L,"test-case-sensitive");
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE orders SET status='accepted' WHERE id=?", order));
        String intent = id();
        jdbc.update("""
            INSERT INTO payment_intents(
              id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,?,?,?)
            """,intent,order,"CASH",10000L,"AWAITING_CASH");
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE payment_intents SET aggregate_status='paid' WHERE id=?",intent));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE payment_intents SET method_selected='cash' WHERE id=?",intent));
    }

    @Test
    void coreForeignKeysCheckConstraintsAndStaffRolesAreEnforced() {
        assertEquals(5, jdbc.queryForObject(
            "SELECT COUNT(*) FROM staff_roles", Integer.class));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO payment_intents(
                    id,order_id,method_selected,amount_due_idr,aggregate_status)
                VALUES (?,?,?,?,?)
                """, id(), id(), "CASH", 10L, "AWAITING_CASH"));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
                id(), "bad-" + id(), "bad-" + id(), -1));
    }
}
