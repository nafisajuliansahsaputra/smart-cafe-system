package com.natsx.smartcafe.common.db;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Read JDBC temporal values explicitly without relying on driver-specific
 * queryForMap classes. MySQL DATETIME(6) values in our schema are UTC
 * wall-clock timestamps, while PostgreSQL timestamptz has an actual offset.
 */
public final class JdbcUtcTime {
    private JdbcUtcTime() {}

    public static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unsupported JDBC timestamp representation");
    }
}
