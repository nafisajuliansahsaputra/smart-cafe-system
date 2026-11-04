package com.natsx.smartcafe.common.db;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Explicit UUID decoding for PostgreSQL's native UUID and MySQL 8 CHAR(36).
 * Never silently coerce invalid database values or infer identities.
 */
public final class JdbcUuid {
    private JdbcUuid() {}

    public static UUID read(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text) {
            return parse(text);
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length == 16) {
                var bb = ByteBuffer.wrap(bytes);
                return new UUID(bb.getLong(), bb.getLong());
            }
            return parse(new String(bytes, StandardCharsets.US_ASCII));
        }
        throw new SQLException("Unsupported UUID column representation", "22018");
    }

    /**
     * Bind UUID values using the database's actual storage type. MySQL
     * Connector/J serializes a raw java.util.UUID when sent via setObject,
     * which is NOT a valid CHAR(36) value. PostgreSQL requires its native UUID.
     * A null remains null; this method never changes the UUID identity.
     */
    public static Object parameter(UUID value, JdbcSqlDialect.Vendor vendor) {
        return switch (java.util.Objects.requireNonNull(vendor, "vendor")) {
            case POSTGRESQL -> value;
            case MYSQL -> value == null ? null : value.toString();
        };
    }

    private static UUID parse(String value) throws SQLException {
        try {
            // Reject malformed or shortened RFC-style representations.
            if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                throw new IllegalArgumentException("not a 36-character UUID");
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            // Do not echo malformed DB data or any sensitive identifier.
            throw new SQLException("Invalid UUID column representation", "22018", ex);
        }
    }
}
