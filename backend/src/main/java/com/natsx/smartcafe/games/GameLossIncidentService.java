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
            """,Boolean.class,loanId);
        if (Boolean.TRUE.equals(open)) throw conflict("A missing-unit report is already open");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO physical_game_loss_incidents(id,loan_id,reported_by,reason)
            VALUES (?,?,?,?)
            """,id,loanId,staff.staffId(),note);
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
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,incidentId);
        if (loanId==null) throw missing();
        Loan loan=lockLoan(loanId,staff,true);
        Incident incident=jdbc.query("""
            SELECT id,loan_id,status FROM physical_game_loss_incidents
            WHERE id=? FOR UPDATE
            """,rs->rs.next()?new Incident(rs.getObject("id",UUID.class),
                rs.getObject("loan_id",UUID.class),rs.getString("status")):null,incidentId);
        if (incident==null || !incident.loanId().equals(loan.id())) throw missing();
        if (!"OPEN".equals(incident.status())) throw conflict("Loss report has already been reviewed");
        if (!List.of("ASSIGNED","IN_USE","RETURN_PENDING").contains(loan.status()))
            throw conflict("Game loan is no longer outstanding");
        if ("CONFIRM_LOST".equals(decision)) {
            if (copyCode==null || !copyCode.equals(loan.code()))
                throw conflict("Verified physical inventory unit code does not match");
            String condition=jdbc.query("""
                SELECT condition FROM physical_game_copies WHERE id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,loan.copyId());
            if (!"AVAILABLE".equals(condition))
                throw conflict("Physical unit condition requires separate investigation");
            jdbc.update("""
                UPDATE physical_game_copies SET condition='LOST' WHERE id=?
                """,loan.copyId());
            jdbc.update("""
                UPDATE physical_game_loans SET status='LOST',lost_at=now(),lost_confirmed_by=?
                WHERE id=?
                """,staff.staffId(),loanId);
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
            staff.staffId(),note,incidentId);
        audit.staff(staff.staffId(),"CONFIRM_LOST".equals(decision)
            ? "GAME_LOSS_CONFIRMED" : "GAME_LOSS_DISMISSED",
            "GAME_LOSS_INCIDENT",incidentId,requestId);
        return one(incidentId);
    }

    private Loan lockLoan(UUID loanId, StaffPrincipal staff, boolean managerOnly) {
        UUID visitId=jdbc.query("""
            SELECT visit_id FROM physical_game_loans WHERE id=?
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,loanId);
        if (visitId==null) throw missing();
        String visitStatus=jdbc.query("""
            SELECT status FROM table_visits WHERE id=? FOR SHARE
            """,rs->rs.next()?rs.getString(1):null,visitId);
        if (!"ACTIVE".equals(visitStatus)) throw conflict("Visit is no longer active");
        if (managerOnly) {
            if (!staff.roles().stream().anyMatch(r->r.equals("MANAGER")||r.equals("ADMIN")))
                throw forbidden();
        } else if (!staff.roles().stream().anyMatch(r->r.equals("CASHIER")
            ||r.equals("MANAGER")||r.equals("ADMIN"))) {
            Boolean owner=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM table_visits WHERE id=? AND opened_by=?)
                """,Boolean.class,visitId,staff.staffId());
            if (!Boolean.TRUE.equals(owner)) throw forbidden();
        }
        Loan loan=jdbc.query("""
            SELECT l.id,l.visit_id,l.copy_id,l.game_id,l.status,c.copy_code
            FROM physical_game_loans l LEFT JOIN physical_game_copies c ON c.id=l.copy_id
            WHERE l.id=? FOR UPDATE OF l
            """,rs->rs.next()?new Loan(rs.getObject("id",UUID.class),
                rs.getObject("visit_id",UUID.class),rs.getObject("copy_id",UUID.class),
                rs.getObject("game_id",UUID.class),rs.getString("status"),
                rs.getString("copy_code")):null,loanId);
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
            """,rs->rs.next()?map(rs):null,id);
    }
    private IncidentView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IncidentView(rs.getObject("id",UUID.class),
            rs.getObject("loan_id",UUID.class),rs.getString("name"),
            rs.getString("copy_code"),rs.getString("table_code"),rs.getString("status"),
            rs.getString("reason"),rs.getString("review_reason"),
            rs.getTimestamp("reported_at").toInstant());
    }
    private String requiredReason(String note) {
        String value=note==null?"":note.strip();
        if(value.length()<5 || value.length()>300
            || value.chars().anyMatch(Character::isISOControl))
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Incident reason must be 5-300 printable characters");
        return value;
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
