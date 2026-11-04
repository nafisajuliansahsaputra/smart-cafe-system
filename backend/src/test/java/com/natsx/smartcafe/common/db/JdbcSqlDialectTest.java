package com.natsx.smartcafe.common.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JdbcSqlDialectTest {
    @Test
    void knownEnginesAreExplicitAndUnknownEnginesFailClosed() {
        assertEquals(JdbcSqlDialect.Vendor.POSTGRESQL,
            JdbcSqlDialect.identify("PostgreSQL"));
        assertEquals(JdbcSqlDialect.Vendor.MYSQL,
            JdbcSqlDialect.identify("MySQL"));
        assertThrows(IllegalStateException.class,
            () -> JdbcSqlDialect.identify("MariaDB"));
        assertThrows(IllegalStateException.class,
            () -> JdbcSqlDialect.identify("H2"));
        assertThrows(IllegalStateException.class,
            () -> JdbcSqlDialect.identify(null));
    }

    @Test
    void mysqlUsesNonDestructiveDuplicateKeyNotInsertIgnore() {
        String throttle = JdbcSqlDialect.ensureStaffLoginThrottle(
            JdbcSqlDialect.Vendor.MYSQL);
        String cart = JdbcSqlDialect.ensureGuestCart(
            JdbcSqlDialect.Vendor.MYSQL);
        assertTrue(throttle.contains("ON DUPLICATE KEY UPDATE login_hash=login_hash"));
        assertTrue(cart.contains("ON DUPLICATE KEY UPDATE guest_id=guest_id"));
        assertEquals(false, throttle.contains("INSERT IGNORE"));
        assertEquals(false, cart.contains("INSERT IGNORE"));
    }

    @Test
    void postgresQueriesRemainIdentical() {
        assertEquals(
            "INSERT INTO staff_login_throttles(login_hash) VALUES (?) ON CONFLICT DO NOTHING",
            JdbcSqlDialect.ensureStaffLoginThrottle(JdbcSqlDialect.Vendor.POSTGRESQL));
        assertEquals(
            "INSERT INTO carts(guest_id,visit_id) VALUES (?,?) ON CONFLICT (guest_id) DO NOTHING",
            JdbcSqlDialect.ensureGuestCart(JdbcSqlDialect.Vendor.POSTGRESQL));
    }
}
