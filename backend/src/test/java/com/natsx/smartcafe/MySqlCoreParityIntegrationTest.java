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
    static final MySQLContainer<?> mysql =
        new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("smart_cafe_mysql_test")
            .withUsername("cafe_test")
            .withPassword("test-only-local-mysql-pass");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        // Never pick PostgreSQL's 31 migrations for a MySQL database.
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/mysql").load();
        var result = flyway.migrate();
        assertEquals(5, result.migrationsExecuted);
        jdbc = new JdbcTemplate(ds);
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
