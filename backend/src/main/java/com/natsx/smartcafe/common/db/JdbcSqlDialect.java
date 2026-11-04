package com.natsx.smartcafe.common.db;

import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Narrow, audited PostgreSQL/MySQL dialect selections.
 *
 * Only use these functions for statements whose transaction/constraint
 * semantics are equivalent on both engines. Never silently replace an
 * advisory transaction lock, stock reservation or payment invariant.
 */
public final class JdbcSqlDialect {
    private JdbcSqlDialect() {}

    public enum Vendor { POSTGRESQL, MYSQL }

    public static Vendor detect(JdbcTemplate jdbc) {
        String name = jdbc.execute(
            (ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        return identify(name);
    }

    public static Vendor identify(String productName) {
        if (productName == null) {
            throw new IllegalStateException("Unsupported SQL backend");
        }
        return switch (productName.trim().toLowerCase(Locale.ROOT)) {
            case "postgresql" -> Vendor.POSTGRESQL;
            case "mysql" -> Vendor.MYSQL;
            default -> throw new IllegalStateException("Unsupported SQL backend");
        };
    }

    /**
     * Race-safe idempotent insert. A duplicate MUST NOT reset a failed
     * login counter. Avoid MySQL INSERT IGNORE, which hides other errors.
     */
    public static String ensureStaffLoginThrottle(Vendor vendor) {
        return switch (vendor) {
            case POSTGRESQL ->
                "INSERT INTO staff_login_throttles(login_hash) VALUES (?) ON CONFLICT DO NOTHING";
            case MYSQL ->
                "INSERT INTO staff_login_throttles(login_hash) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE login_hash=login_hash";
        };
    }

    /**
     * Never overwrite an existing cart's visit ID when an insert collides.
     * A new guest ID belongs to one visit only.
     */
    public static String ensureGuestCart(Vendor vendor) {
        return switch (vendor) {
            case POSTGRESQL ->
                "INSERT INTO carts(guest_id,visit_id) VALUES (?,?) ON CONFLICT (guest_id) DO NOTHING";
            case MYSQL ->
                "INSERT INTO carts(guest_id,visit_id) VALUES (?,?) " +
                "ON DUPLICATE KEY UPDATE guest_id=guest_id";
        };
    }
}
