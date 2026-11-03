package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.audit.AuditTrail;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Real cafe table master data, exclusively available to manager/admin. */
@Service
public class TableAdminService {
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;

    public TableAdminService(JdbcTemplate jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record TableRow(UUID id, String tableCode, String publicAlias,
                           int capacity, String status, boolean activeVisit) {}

    @Transactional(readOnly = true)
    public List<TableRow> list() {
        return jdbc.query("""
            SELECT t.id, t.table_code, t.public_alias, t.capacity, t.status,
                   EXISTS(SELECT 1 FROM table_visits v WHERE v.table_id=t.id
                     AND v.status IN ('ACTIVE','CLOSING')) AS active_visit
            FROM cafe_tables t ORDER BY t.table_code, t.id LIMIT 250
            """, (rs, n) -> new TableRow(rs.getObject("id", UUID.class),
                rs.getString("table_code"), rs.getString("public_alias"),
                rs.getInt("capacity"), rs.getString("status"),
                rs.getBoolean("active_visit")));
    }

    @Transactional
    public TableRow create(String code, String alias, int capacity,
                           StaffPrincipal staff, String requestId) {
        validate(code, alias, capacity);
        UUID id = UUID.randomUUID();
        // No overwrite: immutable table code and public alias protect printed QR/NFC tags.
        int inserted = jdbc.update("""
            INSERT INTO cafe_tables(id,table_code,public_alias,capacity,status)
            VALUES (?,?,?,?,'AVAILABLE')
            ON CONFLICT DO NOTHING
            """, id, code, alias, capacity);
        if (inserted != 1) throw conflict("Table code or public alias already exists");
        audit.staff(staff.staffId(), "TABLE_CREATED", "CAFE_TABLE", id, requestId);
        return new TableRow(id, code, alias, capacity, "AVAILABLE", false);
    }

    @Transactional
    public TableRow update(UUID id, int capacity, String status,
                           StaffPrincipal staff, String requestId) {
        if (capacity < 1 || capacity > 100 ||
            !("AVAILABLE".equals(status) || "OUT_OF_SERVICE".equals(status))) {
            throw invalid();
        }
        // Synchronize with TableVisitService.open(), which locks the same row.
        TableRow current = jdbc.query("""
            SELECT id,table_code,public_alias,capacity,status
            FROM cafe_tables WHERE id=? FOR UPDATE
            """, rs -> rs.next()
                ? new TableRow(rs.getObject("id", UUID.class),
                    rs.getString("table_code"), rs.getString("public_alias"),
                    rs.getInt("capacity"), rs.getString("status"), false)
                : null, id);
        if (current == null) {
            throw new ApiFailureException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Table not found");
        }
        Boolean busy = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM table_visits WHERE table_id=?
               AND status IN ('ACTIVE','CLOSING'))
            """, Boolean.class, id);
        if (Boolean.TRUE.equals(busy)) {
            throw conflict("Close the current table visit before changing table settings");
        }
        if (current.capacity() == capacity && current.status().equals(status)) {
            return current;
        }
        jdbc.update("UPDATE cafe_tables SET capacity=?,status=? WHERE id=?",
            capacity, status, id);
        audit.staff(staff.staffId(), "TABLE_SETTINGS_CHANGED", "CAFE_TABLE", id, requestId);
        return new TableRow(id, current.tableCode(), current.publicAlias(),
            capacity, status, false);
    }

    private void validate(String code, String alias, int capacity) {
        if (code == null || !code.matches("[A-Z0-9][A-Z0-9-]{0,39}")
            || alias == null || !alias.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
            || alias.length() > 100 || capacity < 1 || capacity > 100) {
            throw invalid();
        }
    }

    private ApiFailureException invalid() {
        return new ApiFailureException(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
            "Invalid table configuration");
    }

    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
