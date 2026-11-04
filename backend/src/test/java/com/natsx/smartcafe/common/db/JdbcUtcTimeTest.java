package com.natsx.smartcafe.common.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JdbcUtcTimeTest {
    @Test
    void mysqlAndPostgresDateTimeRepresentationsDecodeAsSameInstant() {
        Instant instant = Instant.parse("2026-11-05T03:04:05.123456Z");
        assertEquals(instant, JdbcUtcTime.toInstant(Timestamp.from(instant)));
        assertEquals(instant, JdbcUtcTime.toInstant(
            LocalDateTime.ofInstant(instant, ZoneOffset.UTC)));
        assertEquals(instant, JdbcUtcTime.toInstant(
            OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(7))));
        assertEquals(instant, JdbcUtcTime.toInstant(instant));
        assertNull(JdbcUtcTime.toInstant(null));
    }

    @Test
    void neverSilentlyInferTimeZoneFromUnrecognizedJdbcData() {
        assertThrows(IllegalStateException.class,
            () -> JdbcUtcTime.toInstant("2026-11-05 10:04:05"));
    }
}
