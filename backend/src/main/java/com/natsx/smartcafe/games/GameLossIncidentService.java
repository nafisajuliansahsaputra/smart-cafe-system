package com.natsx.smartcafe.games;

import com.natsx.smartcafe.audit.AuditTrail;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.common.events.OutboxEvents;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loss reports are allegations about missing property, not findings about
 * borrower responsibility. Only a manager/admin can confirm inventory loss.
 * Every mutation locks visit -> loan -> incident -> copy, consistent with
 * checkout/physical return and table closure.
 */
@Service
public class GameLossIncidentService {
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;
    private final OutboxEvents events;

    public GameLossIncidentService(JdbcTemplate jdbc, AuditTrail audit, OutboxEvents events) {
        this.jdbc=jdbc;
        this.audit=audit;
        this.events=events;
    }

    public record IncidentView(UUID id, UUID loanId, String gameName,
            String copyCode, String tableCode, String status, String reason,
            String reviewReason, Instant reportedAt) {}
    public record IncidentActivity(UUID id, String operation, String actorLogin,
                                   Instant occurredAt, String correlationId) {}
    public record IncidentEvidence(IncidentView incident, String reporterLogin,
                                   String reviewerLogin, Instant reviewedAt,
                                   List<IncidentActivity> activity) {}
    public record IncidentPage(List<IncidentView> items, int page,
                               boolean hasNext, long openCount) {}
    /** Minimal operational notification. Never return complaint notes,
     * guest identity, phone, payment info, or access credentials.
     */
    public record OpenIncidentPreview(String gameName, String copyCode,
                                      String tableCode, Instant reportedAt) {}
    public record OpenIncidentSummary(long openCount,
                                      List<OpenIncidentPreview> recent) {}
    private record Loan(UUID id, UUID visitId, UUID copyId, UUID gameId,
                        String status, String code) {}
    private record Incident(UUID id, UUID loanId, String status) {}

    /** Scoped to opener or supervisor. A report never marks a game returned. */
    @Transactional
    public IncidentView report(UUID loanId, String reason, StaffPrincipal staff, String requestId) {
        String note=requiredReason(reason);
        Loan loan=lockLoan(loanId,staff,false);
        if (!List.of("ASSIGNED","IN_USE","RETURN_PENDING").contains(loan.status()))
            throw conflict("No allocated physical unit is missing");
        Boolean open=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_game_loss_incidents
                          WHERE loan_id=? AND status='OPEN')
            """,Boolean.class,dbUuid(loanId));
        if (Boolean.TRUE.equals(open)) throw conflict("A missing-unit report is already open");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO physical_game_loss_incidents(id,loan_id,reported_by,reason)
            VALUES (?,?,?,?)
            """,dbUuid(id),dbUuid(loanId),dbUuid(staff.staffId()),note);
        events.gameLoan(loanId,"GAME_LOSS_REPORTED");
        audit.staff(staff.staffId(),"GAME_LOSS_REPORTED","GAME_LOSS_INCIDENT",id,requestId);
        return one(id);
    }

    /** Manager sees only unit/incident details, never borrower identity or phone. */
    @Transactional(readOnly=true)
    public List<IncidentView> reviewQueue() {
        return jdbc.query("""
            SELECT i.id,i.loan_id,g.name,c.copy_code,t.table_code,i.status,
                   i.reason,i.review_reason,i.reported_at
            FROM physical_game_loss_incidents i
            JOIN physical_game_loans l ON l.id=i.loan_id
            JOIN physical_game_copies c ON c.id=l.copy_id
            JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            ORDER BY (i.status='OPEN') DESC,i.reported_at DESC,i.id DESC LIMIT 100
            """,(rs,n)->map(rs));
    }

    /** Manager-only evidence: authoritative incident facts and the existing
     * attributable audit trail. Historical actions remain read-only; no guest,
     * contact, receipt, order or payment records are joined or returned.
     */
    @Transactional(readOnly=true)
    public IncidentEvidence incidentEvidence(UUID id) {
        IncidentEvidenceHeader detail=jdbc.query("""
            SELECT i.id,i.loan_id,g.name,c.copy_code,t.table_code,i.status,
                   i.reason,i.review_reason,i.reported_at,
                   reporter.login AS reporter_login, reviewer.login AS reviewer_login,
                   i.reviewed_at
            FROM physical_game_loss_incidents i
            JOIN physical_game_loans l ON l.id=i.loan_id
            JOIN physical_game_copies c ON c.id=l.copy_id
            JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            JOIN staff_users reporter ON reporter.id=i.reported_by
            LEFT JOIN staff_users reviewer ON reviewer.id=i.reviewed_by
            WHERE i.id=?
            """,rs->rs.next()?new IncidentEvidenceHeader(
                map(rs),rs.getString("reporter_login"),
                rs.getString("reviewer_login"),
                com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(rs.getObject("reviewed_at"))):null,dbUuid(id));
        if (detail==null) throw missing();
        List<IncidentActivity> trail=jdbc.query("""
            SELECT a.id,a.operation,s.login AS actor_login,a.occurred_at,
                   a.correlation_id
            FROM audit_logs a
            JOIN staff_users s ON s.id=a.actor_id
            WHERE a.resource_type='GAME_LOSS_INCIDENT' AND a.resource_id=?
              AND a.actor_type='STAFF'
              AND a.operation IN ('GAME_LOSS_REPORTED',
                                  'GAME_LOSS_DISMISSED','GAME_LOSS_CONFIRMED')
            ORDER BY a.occurred_at ASC,a.id ASC LIMIT 25
            """,(rs,n)->new IncidentActivity(com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
                rs.getString("operation"),rs.getString("actor_login"),
                com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(rs.getObject("occurred_at")),
                rs.getString("correlation_id")),dbUuid(id));
        return new IncidentEvidence(detail.incident(),detail.reporterLogin(),
            detail.reviewerLogin(),detail.reviewedAt(),trail);
    }
    private record IncidentEvidenceHeader(IncidentView incident,
            String reporterLogin, String reviewerLogin, Instant reviewedAt) {}

    /** Compact manager alert only: a total and at most three recent open
     * serialized units, sourced from PostgreSQL rather than stale UI state.
     * Access is enforced by the admin route security boundary.
     */
    @Transactional(readOnly=true)
    public OpenIncidentSummary openIncidentSummary() {
        Long count=jdbc.queryForObject("""
            SELECT count(*) FROM physical_game_loss_incidents WHERE status='OPEN'
            """,Long.class);
        List<OpenIncidentPreview> recent=jdbc.query("""
            SELECT g.name,c.copy_code,t.table_code,i.reported_at
            FROM physical_game_loss_incidents i
            JOIN physical_game_loans l ON l.id=i.loan_id
            JOIN physical_game_copies c ON c.id=l.copy_id
            JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            WHERE i.status='OPEN'
            ORDER BY i.reported_at DESC,i.id DESC LIMIT 3
            """,(rs,n)->new OpenIncidentPreview(
                rs.getString("name"),rs.getString("copy_code"),
                rs.getString("table_code"),
                com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(rs.getObject("reported_at"))));
        return new OpenIncidentSummary(count==null?0:count,recent);
    }

    /** Full database, manager-only incident search. OPEN first, then newest.
     * Counts all unresolved manager actions independent of current filters.
     * Query text is literal (not a SQL LIKE wildcard); no guest/contact fields.
     */
    @Transactional(readOnly=true)
    public IncidentPage searchIncidents(String search, String status, int page) {
        String q=search==null?"":search.strip();
        String state=status==null?"":status.strip();
        if (q.length()>80 || q.codePoints().anyMatch(Character::isISOControl)
            || page<0 || page>2000
            || !java.util.Set.of("","OPEN","DISMISSED","CONFIRMED_LOST").contains(state))
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Invalid loss incident search or page");
        final int size=25;
        List<IncidentView> rows=jdbc.query("""
            SELECT i.id,i.loan_id,g.name,c.copy_code,t.table_code,i.status,
                   i.reason,i.review_reason,i.reported_at
            FROM physical_game_loss_incidents i
            JOIN physical_game_loans l ON l.id=i.loan_id
            JOIN physical_game_copies c ON c.id=l.copy_id
            JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            JOIN staff_users reporter ON reporter.id=i.reported_by
            WHERE (?='' OR i.status=?)
              AND (?='' OR POSITION(LOWER(?) IN LOWER(g.name))>0
                        OR POSITION(LOWER(?) IN LOWER(c.copy_code))>0
                        OR POSITION(LOWER(?) IN LOWER(t.table_code))>0
                        OR POSITION(LOWER(?) IN LOWER(reporter.login))>0
                        OR POSITION(LOWER(?) IN LOWER(i.reason))>0
                        OR POSITION(LOWER(?) IN LOWER(COALESCE(i.review_reason,'')))>0)
            ORDER BY (i.status='OPEN') DESC,i.reported_at DESC,i.id DESC
            LIMIT ? OFFSET ?
            """,(rs,n)->map(rs),
            state,state,q,q,q,q,q,q,q,size+1,page*size);
        Long openCount=jdbc.queryForObject("""
            SELECT count(*) FROM physical_game_loss_incidents WHERE status='OPEN'
            """,Long.class);
        return new IncidentPage(
            rows.size()>size?List.copyOf(rows.subList(0,size)):rows,
            page,rows.size()>size && page<2000,openCount==null?0:openCount);
    }

    /** Explicit review cannot overwrite a real returned unit or race with
     * another waiter transition. A confirmed copy stays LOST indefinitely;
     * recovery/restoration is an independent, not-yet-approved workflow.
     */
    @Transactional
    public IncidentView review(UUID incidentId, String decision, String copyCode,
                               String reviewReason, StaffPrincipal staff, String requestId) {
        if (!"DISMISS".equals(decision) && !"CONFIRM_LOST".equals(decision))
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Unsupported missing-game review decision");
        String note=requiredReason(reviewReason);
        UUID loanId=jdbc.query("""
            SELECT loan_id FROM physical_game_loss_incidents WHERE id=?
            """,rs->rs.next()?com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"loan_id"):null,dbUuid(incidentId));
        if (loanId==null) throw missing();
        Loan loan=lockLoan(loanId,staff,true);
        Incident incident=jdbc.query("""
            SELECT id,loan_id,status FROM physical_game_loss_incidents
            WHERE id=? FOR UPDATE
            """,rs->rs.next()?new Incident(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"loan_id"),
                rs.getString("status")):null,dbUuid(incidentId));
        if (incident==null || !incident.loanId().equals(loan.id())) throw missing();
        if (!"OPEN".equals(incident.status())) throw conflict("Loss report has already been reviewed");
        if (!List.of("ASSIGNED","IN_USE","RETURN_PENDING").contains(loan.status()))
            throw conflict("Game loan is no longer outstanding");
        if ("CONFIRM_LOST".equals(decision)) {
            if (copyCode==null || !copyCode.equals(loan.code()))
                throw conflict("Verified physical inventory unit code does not match");
            String condition=jdbc.query("""
                SELECT c.condition FROM physical_game_copies c WHERE c.id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,dbUuid(loan.copyId()));
            if (!"AVAILABLE".equals(condition))
                throw conflict("Physical unit condition requires separate investigation");
            jdbc.update("UPDATE physical_game_copies SET "+conditionColumn()
                +"='LOST' WHERE id=?",dbUuid(loan.copyId()));
            jdbc.update("""
                UPDATE physical_game_loans SET status='LOST',lost_at=now(),lost_confirmed_by=?
                WHERE id=?
                """,dbUuid(staff.staffId()),dbUuid(loanId));
            events.gameInventory(loan.gameId());
            events.gameLoan(loanId,"GAME_LOSS_CONFIRMED");
        } else {
            events.gameLoan(loanId,"GAME_LOSS_DISMISSED");
        }
        jdbc.update("""
            UPDATE physical_game_loss_incidents
            SET status=?,reviewed_by=?,reviewed_at=now(),review_reason=?
            WHERE id=?
            ""","CONFIRM_LOST".equals(decision)?"CONFIRMED_LOST":"DISMISSED",
            dbUuid(staff.staffId()),note,dbUuid(incidentId));
        audit.staff(staff.staffId(),"CONFIRM_LOST".equals(decision)
            ? "GAME_LOSS_CONFIRMED" : "GAME_LOSS_DISMISSED",
            "GAME_LOSS_INCIDENT",incidentId,requestId);
        return one(incidentId);
    }

    private Loan lockLoan(UUID loanId, StaffPrincipal staff, boolean managerOnly) {
        UUID visitId=jdbc.query("""
            SELECT visit_id FROM physical_game_loans WHERE id=?
            """,rs->rs.next()?com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"visit_id"):null,dbUuid(loanId));
        if (visitId==null) throw missing();
        String visitStatus=jdbc.query("""
            SELECT status FROM table_visits WHERE id=? FOR SHARE
            """,rs->rs.next()?rs.getString(1):null,dbUuid(visitId));
        if (!"ACTIVE".equals(visitStatus)) throw conflict("Visit is no longer active");
        if (managerOnly) {
            if (!staff.roles().stream().anyMatch(r->r.equals("MANAGER")||r.equals("ADMIN")))
                throw forbidden();
        } else if (!staff.roles().stream().anyMatch(r->r.equals("CASHIER")
            ||r.equals("MANAGER")||r.equals("ADMIN"))) {
            Boolean owner=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM table_visits WHERE id=? AND opened_by=?)
                """,Boolean.class,dbUuid(visitId),dbUuid(staff.staffId()));
            if (!Boolean.TRUE.equals(owner)) throw forbidden();
        }
        String lockSql=com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)
            == com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? " FOR UPDATE" : " FOR UPDATE OF l";
        Loan loan=jdbc.query("""
            SELECT l.id,l.visit_id,l.copy_id,l.game_id,l.status,c.copy_code
            FROM physical_game_loans l LEFT JOIN physical_game_copies c ON c.id=l.copy_id
            WHERE l.id=?
            """+lockSql,rs->rs.next()?new Loan(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"visit_id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"copy_id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"game_id"),
                rs.getString("status"),rs.getString("copy_code")):null,dbUuid(loanId));
        if (loan==null) throw missing();
        return loan;
    }
    private IncidentView one(UUID id) {
        return jdbc.query("""
            SELECT i.id,i.loan_id,g.name,c.copy_code,t.table_code,i.status,
                   i.reason,i.review_reason,i.reported_at
            FROM physical_game_loss_incidents i
            JOIN physical_game_loans l ON l.id=i.loan_id
            JOIN physical_game_copies c ON c.id=l.copy_id
            JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            WHERE i.id=?
            """,rs->rs.next()?map(rs):null,dbUuid(id));
    }
    private IncidentView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IncidentView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
            com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"loan_id"),rs.getString("name"),
            rs.getString("copy_code"),rs.getString("table_code"),rs.getString("status"),
            rs.getString("reason"),rs.getString("review_reason"),
            com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(rs.getObject("reported_at")));
    }
    private String requiredReason(String note) {
        String value=note==null?"":note.strip();
        if(value.length()<5 || value.length()>300
            || value.chars().anyMatch(Character::isISOControl))
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Incident reason must be 5-300 printable characters");
        return value;
    }
    private Object dbUuid(UUID id) {
        return com.natsx.smartcafe.common.db.JdbcUuid.parameter(
            id, com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc));
    }

    /** The SQL identifier condition is reserved in MySQL; never accept user columns. */
    private String conditionColumn() {
        return com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)
            == com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? "`condition`" : "condition";
    }

    private ApiFailureException forbidden() {
        return new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN","Not responsible for visit");
    }
    private ApiFailureException missing() {
        return new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Game incident not found");
    }
    private ApiFailureException conflict(String msg) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",msg);
    }
}
