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
    void uuidParameterPreservesNativePostgresAndEncodesMySqlChar36() {
        java.util.UUID id = java.util.UUID.randomUUID();
        assertEquals(id, JdbcUuid.parameter(id, JdbcSqlDialect.Vendor.POSTGRESQL));
        assertEquals(id.toString(), JdbcUuid.parameter(id, JdbcSqlDialect.Vendor.MYSQL));
        assertEquals(null, JdbcUuid.parameter(null, JdbcSqlDialect.Vendor.MYSQL));
        assertThrows(NullPointerException.class, () -> JdbcUuid.parameter(id, null));
    }

    @Test
    void guestJoinThrottleHasSafeDialectSpecificStatements() {
        var mysql = JdbcSqlDialect.consumeGuestJoinThrottle(JdbcSqlDialect.Vendor.MYSQL);
        assertTrue(mysql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(mysql.contains("INTERVAL 10 MINUTE"));
        assertTrue(!mysql.contains("RETURNING attempts"));
        assertTrue(!mysql.contains("INSERT IGNORE"));
        assertTrue(JdbcSqlDialect.purgeGuestJoinThrottle(JdbcSqlDialect.Vendor.MYSQL)
            .contains("INTERVAL 2 DAY"));

        var postgres = JdbcSqlDialect.consumeGuestJoinThrottle(JdbcSqlDialect.Vendor.POSTGRESQL);
        assertTrue(postgres.contains("ON CONFLICT (bucket_hash) DO UPDATE"));
        assertTrue(postgres.contains("RETURNING attempts"));
        assertTrue(postgres.contains("interval '10 minutes'"));
        assertTrue(JdbcSqlDialect.purgeGuestJoinThrottle(JdbcSqlDialect.Vendor.POSTGRESQL)
            .contains("interval '2 days'"));
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
