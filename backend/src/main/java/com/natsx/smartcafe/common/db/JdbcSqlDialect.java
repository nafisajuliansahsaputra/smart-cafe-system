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
     * Increment a fixed 10-minute guest-join bucket atomically.
     *
     * PostgreSQL can return the updated row from INSERT ... ON CONFLICT.
     * MySQL needs a separate SELECT ... FOR UPDATE in the same transaction;
     * the unique-key upsert itself serializes concurrent increments.
     * Update attempts BEFORE window_started_at, preserving the previous
     * window boundary for the expiry decision on MySQL.
     */
    public static String consumeGuestJoinThrottle(Vendor vendor) {
        return switch (vendor) {
            case POSTGRESQL -> """
                INSERT INTO guest_join_throttles (bucket_hash,window_started_at,attempts,updated_at)
                VALUES (?,now(),1,now())
                ON CONFLICT (bucket_hash) DO UPDATE SET
                  attempts = CASE
                    WHEN guest_join_throttles.window_started_at <= now() - interval '10 minutes'
                      THEN 1 ELSE guest_join_throttles.attempts + 1 END,
                  window_started_at = CASE
                    WHEN guest_join_throttles.window_started_at <= now() - interval '10 minutes'
                      THEN now() ELSE guest_join_throttles.window_started_at END,
                  updated_at=now()
                RETURNING attempts
                """;
            case MYSQL -> """
                INSERT INTO guest_join_throttles (bucket_hash,window_started_at,attempts,updated_at)
                VALUES (?,NOW(6),1,NOW(6))
                ON DUPLICATE KEY UPDATE
                  attempts = IF(window_started_at <= NOW(6) - INTERVAL 10 MINUTE,
                                1, attempts + 1),
                  window_started_at = IF(window_started_at <= NOW(6) - INTERVAL 10 MINUTE,
                                         NOW(6), window_started_at),
                  updated_at = NOW(6)
                """;
        };
    }

    public static String purgeGuestJoinThrottle(Vendor vendor) {
        return switch (vendor) {
            case POSTGRESQL -> """
                DELETE FROM guest_join_throttles
                WHERE updated_at < now() - interval '2 days'
                """;
            case MYSQL -> """
                DELETE FROM guest_join_throttles
                WHERE updated_at < NOW(6) - INTERVAL 2 DAY
                """;
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
