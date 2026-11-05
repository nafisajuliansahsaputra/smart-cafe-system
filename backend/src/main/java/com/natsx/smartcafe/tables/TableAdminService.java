package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.audit.AuditTrail;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manager-owned physical table inventory.
 * Public aliases are immutable because they may already be printed onto QR/NFC tags.
 */
@Service
public class TableAdminService {
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;

    public TableAdminService(JdbcTemplate jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record TableInfo(UUID id, String tableCode, String publicAlias, int capacity,
                            String status) {}

    @Transactional
    public TableInfo create(String code, String alias, int capacity,
                            StaffPrincipal staff, String requestId) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{0,39}")
            || alias == null || alias.length() > 100
            || !alias.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
            || capacity < 1 || capacity > 30) {
            throw invalid();
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO cafe_tables(id,table_code,public_alias,capacity,status)
                VALUES (?,?,?,?,'AVAILABLE')
                """, dbUuid(id), normalized, alias, capacity);
        } catch (DuplicateKeyException ex) {
            throw new ApiFailureException(HttpStatus.CONFLICT, "CONFLICT",
                "Table code or public alias is already registered");
        }
        audit.staff(staff.staffId(), "CAFE_TABLE_CREATED", "CAFE_TABLE", id, requestId);
        return new TableInfo(id, normalized, alias, capacity, "AVAILABLE");
    }

    @Transactional
    public TableInfo update(UUID id, String status, int capacity,
                            String expectedStatus, int expectedCapacity,
                            StaffPrincipal staff, String requestId) {
        if (id == null || !validStatus(status) || !validStatus(expectedStatus)
            || capacity < 1 || capacity > 30
            || expectedCapacity < 1 || expectedCapacity > 30) {
            throw invalid();
        }
        // Must serialize with TableVisitService.open which locks the same table row.
        TableInfo current = jdbc.query("""
            SELECT id,table_code,public_alias,capacity,status
            FROM cafe_tables WHERE id=? FOR UPDATE
            """, rs -> rs.next() ? new TableInfo(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"), rs.getString("table_code"),
                rs.getString("public_alias"), rs.getInt("capacity"),
                rs.getString("status")) : null, dbUuid(id));
        if (current == null) {
            throw new ApiFailureException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Cafe table not found");
        }
        if (!current.status().equals(expectedStatus)
            || current.capacity() != expectedCapacity) {
            throw new ApiFailureException(HttpStatus.CONFLICT, "CONFLICT",
                "Cafe table changed; refresh before making another edit");
        }
        if ("OUT_OF_SERVICE".equals(status)) {
            Boolean active = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM table_visits
                    WHERE table_id=? AND status IN ('ACTIVE','CLOSING'))
                """, Boolean.class, dbUuid(id));
            if (Boolean.TRUE.equals(active)) {
                throw new ApiFailureException(HttpStatus.CONFLICT, "CONFLICT",
                    "Cannot take a table out of service during an active visit");
            }
        }
        if (!current.status().equals(status) || current.capacity() != capacity) {
            jdbc.update("UPDATE cafe_tables SET status=?, capacity=? WHERE id=?",
                status, capacity, dbUuid(id));
            audit.staff(staff.staffId(), "CAFE_TABLE_UPDATED", "CAFE_TABLE", id, requestId);
        }
        return new TableInfo(id, current.tableCode(), current.publicAlias(), capacity, status);
    }

    /** Preserve native UUIDs on PostgreSQL and CHAR(36) on MySQL. */
    private Object dbUuid(UUID id) {
        return com.natsx.smartcafe.common.db.JdbcUuid.parameter(
            id,com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc));
    }

    private boolean validStatus(String value) {
        return "AVAILABLE".equals(value) || "OUT_OF_SERVICE".equals(value);
    }
    private ApiFailureException invalid() {
        return new ApiFailureException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Invalid table registration or update");
    }
}
