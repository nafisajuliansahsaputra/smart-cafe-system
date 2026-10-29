package com.natsx.smartcafe.games;

import com.natsx.smartcafe.audit.AuditTrail;
import com.natsx.smartcafe.common.events.OutboxEvents;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Physical copy ownership is a database invariant. Every mutation locks
 * the visit before the loan/copy, matching TableVisitService.close().
 * No fees/deposits/damage charges are silently introduced.
 */
@Service
public class GameLoanService {
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;
    private final OutboxEvents events;

    public GameLoanService(JdbcTemplate jdbc, AuditTrail audit, OutboxEvents events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    public record GameView(UUID id, String name, String description, int availableCopies) {}
    public record LoanView(UUID id, UUID gameId, String gameName, String status,
                           String tableCode, String copyCode, Instant requestedAt) {}
    private record Loan(UUID id, UUID visitId, String status, UUID gameId, UUID copyId,
                        UUID guestId, String code) {}

    @Transactional(readOnly = true)
    public List<GameView> catalog() {
        return jdbc.query("""
            SELECT g.id,g.name,g.description,COALESCE((
                SELECT count(*) FROM physical_game_copies c
                WHERE c.game_id=g.id AND c.condition='AVAILABLE'
                  AND NOT EXISTS (
                    SELECT 1 FROM physical_game_loans l
                    WHERE l.copy_id=c.id AND l.status IN ('ASSIGNED','IN_USE','RETURN_PENDING')
                  )
            ),0) AS available
            FROM physical_games g WHERE g.active=true ORDER BY g.name LIMIT 100
            """, (rs,n)->new GameView(rs.getObject("id",UUID.class),
                rs.getString("name"),rs.getString("description"),rs.getInt("available")));
    }

    @Transactional(readOnly = true)
    public List<LoanView> mine(GuestPrincipal guest) {
        return jdbc.query("""
            SELECT l.id,l.game_id,g.name,l.status,t.table_code,
                   CASE WHEN l.status IN ('IN_USE','RETURN_PENDING')
                        THEN c.copy_code ELSE NULL END AS copy_code,
                   l.requested_at
            FROM physical_game_loans l JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            LEFT JOIN physical_game_copies c ON c.id=l.copy_id
            WHERE l.guest_id=? AND l.visit_id=? ORDER BY l.requested_at DESC LIMIT 100
            """, (rs,n)->map(rs),guest.guestId(),guest.visitId());
    }

    @Transactional
    public LoanView request(GuestPrincipal guest, UUID gameId) {
        // Serialize requests for this visit and exclude concurrent table close
        // and duplicate game requests before checking for active loans.
        requireActiveVisitExclusive(guest.visitId());
        Boolean member=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM visit_guests
                          WHERE id=? AND visit_id=?)
            """,Boolean.class,guest.guestId(),guest.visitId());
        if (!Boolean.TRUE.equals(member)) throw missing();
        Boolean available=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_games WHERE id=? AND active=true)
            """,Boolean.class,gameId);
        if (!Boolean.TRUE.equals(available)) throw missing();
        Boolean duplicate=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_game_loans
              WHERE visit_id=? AND game_id=? AND status IN ('REQUESTED','ASSIGNED','IN_USE','RETURN_PENDING'))
            """,Boolean.class,guest.visitId(),gameId);
        if (Boolean.TRUE.equals(duplicate)) throw conflict("A game request is already active for this table");
        // A REQUESTED loan does not reserve a copy. Waiter rechecks units at
        // assignment. A pending request must never masquerade as delivery.
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO physical_game_loans(id,visit_id,guest_id,game_id)
            VALUES (?,?,?,?)
            """,id,guest.visitId(),guest.guestId(),gameId);
        events.gameLoan(id,"GAME_LOAN_REQUESTED");
        return one(id);
    }

    /** Customer can request collection of their own handed-over unit only.
     * This is not a physical return and must never release inventory.
     * Lock visit first to exclude concurrent table close.
     */
    @Transactional
    public LoanView requestReturn(GuestPrincipal guest, UUID loanId) {
        requireActiveVisitExclusive(guest.visitId());
        String state = jdbc.query("""
            SELECT status FROM physical_game_loans
            WHERE id=? AND guest_id=? AND visit_id=?
            FOR UPDATE
            """, rs -> rs.next()?rs.getString(1):null,
            loanId,guest.guestId(),guest.visitId());
        if (state == null) throw missing(); // foreign loans never disclose existence
        if ("RETURN_PENDING".equals(state)) return one(loanId); // idempotent retry
        if (!"IN_USE".equals(state)) throw conflict("Game is not in guest possession");
        jdbc.update("""
            UPDATE physical_game_loans SET status='RETURN_PENDING',
                return_requested_at=now() WHERE id=?
            """,loanId);
        events.gameLoan(loanId,"GAME_LOAN_RETURN_REQUESTED");
        return one(loanId);
    }

    @Transactional(readOnly = true)
    public List<LoanView> tasks(StaffPrincipal staff) {
        boolean supervisor=supervisor(staff);
        return jdbc.query("""
            SELECT l.id,l.game_id,g.name,l.status,t.table_code,
                   c.copy_code,l.requested_at
            FROM physical_game_loans l JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            LEFT JOIN physical_game_copies c ON c.id=l.copy_id
            WHERE l.status IN ('REQUESTED','ASSIGNED','IN_USE','RETURN_PENDING')
            """+(supervisor?"":" AND v.opened_by=? ")+
            " ORDER BY l.requested_at ASC LIMIT 100",
            (rs,n)->map(rs),supervisor?new Object[]{}:new Object[]{staff.staffId()});
    }

    @Transactional
    public LoanView assign(UUID loanId, StaffPrincipal staff, String requestId) {
        Loan loan=lockLoanByVisit(loanId,staff);
        if (!loan.status().equals("REQUESTED")) throw conflict("Game request is not waiting for assignment");
        UUID copy=jdbc.query("""
            SELECT c.id FROM physical_game_copies c
            WHERE c.game_id=? AND c.condition='AVAILABLE'
              AND NOT EXISTS(SELECT 1 FROM physical_game_loans l
                  WHERE l.copy_id=c.id AND l.status IN ('ASSIGNED','IN_USE','RETURN_PENDING'))
            ORDER BY c.copy_code LIMIT 1 FOR UPDATE OF c SKIP LOCKED
            """,rs->rs.next()?rs.getObject("id",UUID.class):null,loan.gameId());
        if (copy==null) throw conflict("No available physical copy for this game");
        jdbc.update("""
            UPDATE physical_game_loans SET copy_id=?,status='ASSIGNED',
                assigned_at=now(),assigned_by=? WHERE id=?
            """,copy,staff.staffId(),loanId);
        events.gameLoan(loanId,"GAME_LOAN_ASSIGNED");
        audit.staff(staff.staffId(),"GAME_COPY_ASSIGNED","GAME_LOAN",loanId,requestId);
        return one(loanId);
    }

    @Transactional
    public LoanView handover(UUID loanId, String confirmedCopyCode, StaffPrincipal staff,
                             String requestId) {
        Loan loan=lockLoanByVisit(loanId,staff);
        if (!loan.status().equals("ASSIGNED")) throw conflict("Game is not assigned");
        // Require staff to physically read the correct serial number.
        if (confirmedCopyCode==null || !confirmedCopyCode.equals(loan.code()))
            throw conflict("Physical game copy code does not match");
        jdbc.update("""
            UPDATE physical_game_loans SET status='IN_USE',
                handed_over_at=now(),handed_over_by=? WHERE id=?
            """,staff.staffId(),loanId);
        events.gameLoan(loanId,"GAME_LOAN_HANDED_OVER");
        audit.staff(staff.staffId(),"GAME_COPY_HANDED_OVER","GAME_LOAN",loanId,requestId);
        return one(loanId);
    }

    @Transactional
    public LoanView returnCopy(UUID loanId, String confirmedCopyCode, StaffPrincipal staff,
                               String requestId) {
        Loan loan=lockLoanByVisit(loanId,staff);
        if (!loan.status().equals("IN_USE") && !loan.status().equals("RETURN_PENDING"))
            throw conflict("Game has not been handed over");
        if (confirmedCopyCode==null || !confirmedCopyCode.equals(loan.code()))
            throw conflict("Physical game copy code does not match");
        jdbc.update("""
            UPDATE physical_game_loans SET status='RETURNED',
                returned_at=now(),returned_by=? WHERE id=?
            """,staff.staffId(),loanId);
        events.gameLoan(loanId,"GAME_LOAN_RETURNED");
        audit.staff(staff.staffId(),"GAME_COPY_RETURNED_INSPECTED","GAME_LOAN",loanId,requestId);
        return one(loanId);
    }

    @Transactional
    public LoanView cancel(UUID loanId, StaffPrincipal staff, String requestId) {
        Loan loan=lockLoanByVisit(loanId,staff);
        if (!loan.status().equals("REQUESTED")) throw conflict("Only unassigned requests may be cancelled");
        jdbc.update("UPDATE physical_game_loans SET status='CANCELLED' WHERE id=?",loanId);
        events.gameLoan(loanId,"GAME_LOAN_CANCELLED");
        audit.staff(staff.staffId(),"GAME_REQUEST_CANCELLED","GAME_LOAN",loanId,requestId);
        return one(loanId);
    }

    @Transactional
    public GameView addGame(String name, String description, StaffPrincipal staff, String requestId) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO physical_games(id,name,description) VALUES (?,?,?)",
            id,name.strip(),description==null?null:description.strip());
        audit.staff(staff.staffId(),"GAME_CATALOG_CREATED","PHYSICAL_GAME",id,requestId);
        return new GameView(id,name.strip(),description==null?null:description.strip(),0);
    }

    @Transactional
    public UUID addCopy(UUID gameId, String code, StaffPrincipal staff, String requestId) {
        Boolean exists=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_games WHERE id=? AND active=true)
            """,Boolean.class,gameId);
        if (!Boolean.TRUE.equals(exists)) throw missing();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO physical_game_copies(id,game_id,copy_code) VALUES (?,?,?)
            """,id,gameId,code.strip());
        audit.staff(staff.staffId(),"GAME_COPY_REGISTERED","PHYSICAL_GAME_COPY",id,requestId);
        return id;
    }

    private void requireActiveVisitExclusive(UUID visitId) {
        String state=jdbc.query("""
            SELECT status FROM table_visits WHERE id=? FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null,visitId);
        if (!"ACTIVE".equals(state)) throw conflict("Table visit is no longer active");
    }

    private void requireActiveVisit(UUID visitId) {
        String state=jdbc.query("""
            SELECT status FROM table_visits WHERE id=? FOR SHARE
            """,rs->rs.next()?rs.getString(1):null,visitId);
        if (!"ACTIVE".equals(state)) throw conflict("Table visit is no longer active");
    }

    private Loan lockLoanByVisit(UUID loanId, StaffPrincipal staff) {
        UUID visitId=jdbc.query("""
            SELECT visit_id FROM physical_game_loans WHERE id=?
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,loanId);
        if (visitId==null) throw missing();
        requireActiveVisit(visitId);
        if (!supervisor(staff)) {
            Boolean mine=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM table_visits WHERE id=? AND opened_by=?)
                """,Boolean.class,visitId,staff.staffId());
            if (!Boolean.TRUE.equals(mine)) throw new ApiFailureException(
                HttpStatus.FORBIDDEN,"FORBIDDEN","Waiter is not responsible for this visit");
        }
        return jdbc.query("""
            SELECT l.id,l.visit_id,l.guest_id,l.game_id,l.copy_id,l.status,c.copy_code
            FROM physical_game_loans l
            LEFT JOIN physical_game_copies c ON c.id=l.copy_id
            WHERE l.id=? FOR UPDATE OF l
            """,rs->rs.next()?new Loan(rs.getObject("id",UUID.class),
                rs.getObject("visit_id",UUID.class),rs.getString("status"),
                rs.getObject("game_id",UUID.class),rs.getObject("copy_id",UUID.class),
                rs.getObject("guest_id",UUID.class),rs.getString("copy_code")):null,loanId);
    }

    private LoanView one(UUID loanId) {
        return jdbc.query("""
            SELECT l.id,l.game_id,g.name,l.status,t.table_code,c.copy_code,l.requested_at
            FROM physical_game_loans l JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id JOIN cafe_tables t ON t.id=v.table_id
            LEFT JOIN physical_game_copies c ON c.id=l.copy_id
            WHERE l.id=?
            """,rs->rs.next()?map(rs):null,loanId);
    }
    private LoanView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LoanView(rs.getObject("id",UUID.class),rs.getObject("game_id",UUID.class),
            rs.getString("name"),rs.getString("status"),rs.getString("table_code"),
            rs.getString("copy_code"),rs.getTimestamp("requested_at").toInstant());
    }
    private boolean supervisor(StaffPrincipal staff) {
        return staff.roles().stream().anyMatch(r ->
            r.equals("CASHIER") || r.equals("MANAGER") || r.equals("ADMIN"));
    }
    private ApiFailureException missing() {
        return new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Game or loan not found");
    }
    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",message);
    }
}
