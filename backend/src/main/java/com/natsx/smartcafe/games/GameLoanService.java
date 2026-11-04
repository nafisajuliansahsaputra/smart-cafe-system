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
    public record CopyView(UUID id, UUID gameId, String gameName,
                           String copyCode, String condition) {}
    public record CopyPage(List<CopyView> items, int page, boolean hasNext) {}
    public record ConditionHistoryView(UUID id, UUID copyId, String gameName,
            String copyCode, String beforeCondition, String afterCondition,
            String reason, String actorLogin, Instant recordedAt) {}
    public record ConditionHistoryPage(List<ConditionHistoryView> items, int page, boolean hasNext) {}
    private record Loan(UUID id, UUID visitId, String status, UUID gameId, UUID copyId,
                        UUID guestId, String code) {}
    private record RecoveryCopy(UUID gameId, String code, String condition) {}

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
            """, (rs,n)->new GameView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
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
            """, (rs,n)->map(rs),dbUuid(guest.guestId()),dbUuid(guest.visitId()));
    }

    @Transactional
    public LoanView request(GuestPrincipal guest, UUID gameId) {
        // Serialize requests for this visit and exclude concurrent table close
        // and duplicate game requests before checking for active loans.
        requireActiveVisitExclusive(guest.visitId());
        Boolean member=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM visit_guests
                          WHERE id=? AND visit_id=?)
            """,Boolean.class,dbUuid(guest.guestId()),dbUuid(guest.visitId()));
        if (!Boolean.TRUE.equals(member)) throw missing();
        Boolean available=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_games WHERE id=? AND active=true)
            """,Boolean.class,dbUuid(gameId));
        if (!Boolean.TRUE.equals(available)) throw missing();
        Boolean duplicate=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_game_loans
              WHERE visit_id=? AND game_id=? AND status IN ('REQUESTED','ASSIGNED','IN_USE','RETURN_PENDING'))
            """,Boolean.class,dbUuid(guest.visitId()),dbUuid(gameId));
        if (Boolean.TRUE.equals(duplicate)) throw conflict("A game request is already active for this table");
        // A REQUESTED loan does not reserve a copy. Waiter rechecks units at
        // assignment. A pending request must never masquerade as delivery.
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO physical_game_loans(id,visit_id,guest_id,game_id)
            VALUES (?,?,?,?)
            """,dbUuid(id),dbUuid(guest.visitId()),dbUuid(guest.guestId()),dbUuid(gameId));
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
            dbUuid(loanId),dbUuid(guest.guestId()),dbUuid(guest.visitId()));
        if (state == null) throw missing(); // foreign loans never disclose existence
        if ("RETURN_PENDING".equals(state)) return one(loanId); // idempotent retry
        if (!"IN_USE".equals(state)) throw conflict("Game is not in guest possession");
        jdbc.update("""
            UPDATE physical_game_loans SET status='RETURN_PENDING',
                return_requested_at=now() WHERE id=?
            """,dbUuid(loanId));
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
            (rs,n)->map(rs),supervisor?new Object[]{}:new Object[]{dbUuid(staff.staffId())});
    }

    @Transactional
    public LoanView assign(UUID loanId, StaffPrincipal staff, String requestId) {
        Loan loan=lockLoanByVisit(loanId,staff);
        if (!loan.status().equals("REQUESTED")) throw conflict("Game request is not waiting for assignment");
        var vendor=com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        // A row lock on the candidate copy plus the unique active-copy
        // constraint protect against two physical loans of the same unit.
        String lockCopy = vendor==com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? " FOR UPDATE SKIP LOCKED" : " FOR UPDATE OF c SKIP LOCKED";
        UUID copy=jdbc.query("""
            SELECT c.id FROM physical_game_copies c
            WHERE c.game_id=? AND c.condition='AVAILABLE'
              AND NOT EXISTS(SELECT 1 FROM physical_game_loans l
                  WHERE l.copy_id=c.id AND l.status IN ('ASSIGNED','IN_USE','RETURN_PENDING'))
            ORDER BY c.copy_code LIMIT 1
            """+lockCopy,rs->rs.next()?
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"):null,
                dbUuid(loan.gameId()));
        if (copy==null) throw conflict("No available physical copy for this game");
        jdbc.update("""
            UPDATE physical_game_loans SET copy_id=?,status='ASSIGNED',
                assigned_at=now(),assigned_by=? WHERE id=?
            """,dbUuid(copy),dbUuid(staff.staffId()),dbUuid(loanId));
        events.gameLoan(loanId,"GAME_LOAN_ASSIGNED");
        events.gameInventory(loan.gameId());
        audit.staff(staff.staffId(),"GAME_COPY_ASSIGNED","GAME_LOAN",loanId,requestId);
        return one(loanId);
    }

    @Transactional
    public LoanView handover(UUID loanId, String confirmedCopyCode, StaffPrincipal staff,
                             String requestId) {
        Loan loan=lockLoanByVisit(loanId,staff);
        if (!loan.status().equals("ASSIGNED")) throw conflict("Game is not assigned");
        requireNoPendingLossReport(loanId);
        // Require staff to physically read the correct serial number.
        if (confirmedCopyCode==null || !confirmedCopyCode.equals(loan.code()))
            throw conflict("Physical game copy code does not match");
        jdbc.update("""
            UPDATE physical_game_loans SET status='IN_USE',
                handed_over_at=now(),handed_over_by=? WHERE id=?
            """,dbUuid(staff.staffId()),dbUuid(loanId));
        events.gameLoan(loanId,"GAME_LOAN_HANDED_OVER");
        audit.staff(staff.staffId(),"GAME_COPY_HANDED_OVER","GAME_LOAN",loanId,requestId);
        return one(loanId);
    }

    /** A clean return releases the unit; a damaged return quarantines it
     * in the SAME transaction before a new assignment can observe it.
     */
    @Transactional
    public LoanView returnCopy(UUID loanId, String confirmedCopyCode, StaffPrincipal staff,
                               String requestId) {
        return finishReturn(loanId,confirmedCopyCode,null,staff,requestId);
    }

    @Transactional
    public LoanView returnDamagedCopy(UUID loanId, String confirmedCopyCode,
                                      String damageReason, StaffPrincipal staff,
                                      String requestId) {
        return finishReturn(loanId,confirmedCopyCode,requiredReason(damageReason),
            staff,requestId);
    }

    private LoanView finishReturn(UUID loanId, String confirmedCopyCode, String damageReason,
                                  StaffPrincipal staff, String requestId) {
        Loan loan=lockLoanByVisit(loanId,staff);
        if (!loan.status().equals("IN_USE") && !loan.status().equals("RETURN_PENDING"))
            throw conflict("Game has not been handed over");
        if (confirmedCopyCode==null || !confirmedCopyCode.equals(loan.code()))
            throw conflict("Physical game copy code does not match");
        requireNoPendingLossReport(loanId);
        // Serialize inspection with manager quarantine/restoration and
        // automatic assignment. Never release an already non-available copy.
        String condition=jdbc.query("""
            SELECT condition FROM physical_game_copies WHERE id=? FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null,dbUuid(loan.copyId()));
        if (!"AVAILABLE".equals(condition))
            throw conflict("Physical game copy is not available for clean return");
        if (damageReason!=null) {
            jdbc.update("""
                UPDATE physical_game_copies SET condition='DAMAGED' WHERE id=?
                """,dbUuid(loan.copyId()));
            recordCondition(loan.copyId(),loanId,staff.staffId(),
                "AVAILABLE","DAMAGED",damageReason);
            audit.staff(staff.staffId(),"GAME_COPY_QUARANTINED_AT_RETURN",
                "PHYSICAL_GAME_COPY",loan.copyId(),requestId);
        }
        jdbc.update("""
            UPDATE physical_game_loans SET status='RETURNED',
                returned_at=now(),returned_by=? WHERE id=?
            """,dbUuid(staff.staffId()),dbUuid(loanId));
        events.gameLoan(loanId,"GAME_LOAN_RETURNED");
        events.gameInventory(loan.gameId());
        audit.staff(staff.staffId(),damageReason==null
            ? "GAME_COPY_RETURNED_INSPECTED" : "GAME_COPY_RETURNED_QUARANTINED",
            "GAME_LOAN",loanId,requestId);
        return one(loanId);
    }

    /** Manager/admin inventory remediation is never a guest/loan operation.
     * Quarantine only unborrowed AVAILABLE units; restore only DAMAGED units.
     * A reason and before/after history are mandatory on both transitions.
     */
    @Transactional(readOnly = true)
    public List<CopyView> copies() {
        return jdbc.query("""
            SELECT c.id,c.game_id,g.name,c.copy_code,c.condition
            FROM physical_game_copies c JOIN physical_games g ON g.id=c.game_id
            ORDER BY g.name,c.copy_code LIMIT 250
            """,(rs,n)->new CopyView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "game_id"),rs.getString("name"),
                rs.getString("copy_code"),rs.getString("condition")));
    }

    /** A bounded server-side search, not a filtered version of the first 250 rows.
     * The cursor is a page number capped to avoid unbounded OFFSET work.
     * POSITION treats SQL %, _ and backslashes as literal user text.
     */
    @Transactional(readOnly = true)
    public CopyPage searchCopies(String search, String condition, int page) {
        String q=search==null?"":search.strip();
        String filter=condition==null?"":condition.strip();
        if (q.length()>80 || q.codePoints().anyMatch(Character::isISOControl)
            || page<0 || page>2000 ||
            !java.util.Set.of("","AVAILABLE","DAMAGED","LOST","RETIRED").contains(filter)) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Invalid inventory search or page");
        }
        final int size=25;
        List<CopyView> rows=jdbc.query("""
            SELECT c.id,c.game_id,g.name,c.copy_code,c.condition
            FROM physical_game_copies c JOIN physical_games g ON g.id=c.game_id
            WHERE (?='' OR POSITION(LOWER(?) IN LOWER(c.copy_code))>0
                         OR POSITION(LOWER(?) IN LOWER(g.name))>0)
              AND (?='' OR c.condition=?)
            ORDER BY c.copy_code,c.id
            LIMIT ? OFFSET ?
            """,(rs,n)->new CopyView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "game_id"),rs.getString("name"),
                rs.getString("copy_code"),rs.getString("condition")),
            q,q,q,filter,filter,size+1,page*size);
        boolean hasNext=rows.size()>size && page<2000; // Never link beyond validated cap.
        return new CopyPage(rows.size()>size?List.copyOf(rows.subList(0,size)):rows,page,hasNext);
    }

    /** Bounded, manager-only inventory audit. No guest identity or payment data. */
    @Transactional(readOnly = true)
    public List<ConditionHistoryView> conditionHistory() {
        return jdbc.query("""
            SELECT e.id,e.copy_id,g.name,c.copy_code,
                   e.before_condition,e.after_condition,e.reason,
                   s.login AS actor_login,e.recorded_at
            FROM physical_game_copy_condition_events e
            JOIN physical_game_copies c ON c.id=e.copy_id
            JOIN physical_games g ON g.id=c.game_id
            JOIN staff_users s ON s.id=e.actor_id
            ORDER BY e.recorded_at DESC,e.id DESC LIMIT 100
            """,(rs,n)->new ConditionHistoryView(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "copy_id"),
                rs.getString("name"),rs.getString("copy_code"),
                rs.getString("before_condition"),rs.getString("after_condition"),
                rs.getString("reason"),rs.getString("actor_login"),
                com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(rs.getObject("recorded_at"))));
    }

    /** Bounded manager-only audit history: search is over the full event table,
     * not an in-memory filter of the latest 100 rows. No delete/edit path.
     */
    @Transactional(readOnly = true)
    public ConditionHistoryPage searchConditionHistory(String search, String change, int page) {
        String q=search==null?"":search.strip();
        String next=change==null?"":change.strip();
        if (q.length()>80 || q.codePoints().anyMatch(Character::isISOControl)
            || page<0 || page>2000
            || !java.util.Set.of("","AVAILABLE","DAMAGED").contains(next)) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Invalid condition history search or page");
        }
        final int size=25;
        List<ConditionHistoryView> rows=jdbc.query("""
            SELECT e.id,e.copy_id,g.name,c.copy_code,
                   e.before_condition,e.after_condition,e.reason,
                   s.login AS actor_login,e.recorded_at
            FROM physical_game_copy_condition_events e
            JOIN physical_game_copies c ON c.id=e.copy_id
            JOIN physical_games g ON g.id=c.game_id
            JOIN staff_users s ON s.id=e.actor_id
            WHERE (?='' OR POSITION(LOWER(?) IN LOWER(c.copy_code))>0
                         OR POSITION(LOWER(?) IN LOWER(g.name))>0
                         OR POSITION(LOWER(?) IN LOWER(s.login))>0
                         OR POSITION(LOWER(?) IN LOWER(e.reason))>0)
              AND (?='' OR e.after_condition=?)
            ORDER BY e.recorded_at DESC,e.id DESC
            LIMIT ? OFFSET ?
            """,(rs,n)->new ConditionHistoryView(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "copy_id"),
                rs.getString("name"),rs.getString("copy_code"),
                rs.getString("before_condition"),rs.getString("after_condition"),
                rs.getString("reason"),rs.getString("actor_login"),
                com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(rs.getObject("recorded_at"))),
            q,q,q,q,q,next,next,size+1,page*size);
        boolean hasNext=rows.size()>size && page<2000;
        return new ConditionHistoryPage(
            rows.size()>size?List.copyOf(rows.subList(0,size)):rows,page,hasNext);
    }

    @Transactional
    public CopyView changeCopyCondition(UUID copyId, String desired, String reason,
                                        StaffPrincipal staff, String requestId) {
        if (!"AVAILABLE".equals(desired) && !"DAMAGED".equals(desired))
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",
                "Only AVAILABLE and DAMAGED transitions are supported");
        String note=requiredReason(reason);
        String current=jdbc.query("""
            SELECT condition FROM physical_game_copies WHERE id=? FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null,dbUuid(copyId));
        if (current==null) throw missing();
        if (!("AVAILABLE".equals(current) && "DAMAGED".equals(desired)
              || "DAMAGED".equals(current) && "AVAILABLE".equals(desired)))
            throw conflict("Only quarantine or inspected restoration is permitted");
        Boolean active=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_game_loans
                WHERE copy_id=? AND status IN ('ASSIGNED','IN_USE','RETURN_PENDING'))
            """,Boolean.class,dbUuid(copyId));
        if (Boolean.TRUE.equals(active))
            throw conflict("Cannot change condition while physical copy is assigned");
        jdbc.update("UPDATE physical_game_copies SET condition=? WHERE id=?",desired,dbUuid(copyId));
        recordCondition(copyId,null,staff.staffId(),current,desired,note);
        UUID gameId=jdbc.query("""
            SELECT game_id FROM physical_game_copies WHERE id=?
            """,rs->rs.next()?com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"game_id"):null,
            dbUuid(copyId));
        events.gameInventory(gameId);
        audit.staff(staff.staffId(),"DAMAGED".equals(desired)
            ? "GAME_COPY_QUARANTINED" : "GAME_COPY_RESTORED_INSPECTED",
            "PHYSICAL_GAME_COPY",copyId,requestId);
        return jdbc.query("""
            SELECT c.id,c.game_id,g.name,c.copy_code,c.condition
            FROM physical_game_copies c JOIN physical_games g ON g.id=c.game_id
            WHERE c.id=?
            """,rs->rs.next()?new CopyView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "game_id"),rs.getString("name"),
                rs.getString("copy_code"),rs.getString("condition")):null,dbUuid(copyId));
    }

    /** Physical recovery does not erase a confirmed-loss incident or alter
     * historical LOST loans. Always quarantine the found copy first.
     * Manager must separately inspect DAMAGED -> AVAILABLE afterward.
     * Copy row lock serializes with assignment and condition changes.
     */
    @Transactional
    public CopyView recoverLostCopy(UUID copyId, String verifiedCopyCode, String reason,
                                   StaffPrincipal staff, String requestId) {
        if (!staff.roles().stream().anyMatch(r->r.equals("MANAGER")||r.equals("ADMIN")))
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Only manager/admin can recover missing physical equipment");
        String note=requiredReason(reason);
        RecoveryCopy copy=jdbc.query("""
            SELECT game_id,copy_code,condition
            FROM physical_game_copies WHERE id=? FOR UPDATE
            """,rs->rs.next()?new RecoveryCopy(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "game_id"),rs.getString("copy_code"),
                rs.getString("condition")):null,dbUuid(copyId));
        if (copy==null) throw missing();
        if (!"LOST".equals(copy.condition())) throw conflict("Copy is not marked LOST");
        if (verifiedCopyCode==null || !verifiedCopyCode.equals(copy.code()))
            throw conflict("Verified physical game copy code does not match");
        // The LOST classification must come from an actual, explicitly
        // reviewed incident. Do not permit recovery of arbitrary legacy flags.
        UUID loanId=jdbc.query("""
            SELECT l.id FROM physical_game_loans l
            JOIN physical_game_loss_incidents i ON i.loan_id=l.id
            WHERE l.copy_id=? AND l.status='LOST' AND i.status='CONFIRMED_LOST'
            ORDER BY i.reviewed_at DESC,i.id DESC LIMIT 1
            """,rs->rs.next()?com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"):null,
            dbUuid(copyId));
        if (loanId==null) throw conflict("Copy has no verified lost-loan incident");
        Boolean active=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_game_loans
                WHERE copy_id=? AND status IN ('ASSIGNED','IN_USE','RETURN_PENDING'))
            """,Boolean.class,dbUuid(copyId));
        if (Boolean.TRUE.equals(active))
            throw conflict("Cannot recover while copy is assigned to an active visit");
        jdbc.update("UPDATE physical_game_copies SET condition='DAMAGED' WHERE id=?",dbUuid(copyId));
        // Before/after inspection history is permanent; original LOST loan
        // and confirmed incident are deliberately never rewritten.
        recordCondition(copyId,loanId,staff.staffId(),"LOST","DAMAGED",note);
        audit.staff(staff.staffId(),"GAME_COPY_FOUND_QUARANTINED",
            "PHYSICAL_GAME_COPY",copyId,requestId);
        events.gameInventory(copy.gameId());
        return jdbc.query("""
            SELECT c.id,c.game_id,g.name,c.copy_code,c.condition
            FROM physical_game_copies c JOIN physical_games g ON g.id=c.game_id
            WHERE c.id=?
            """,rs->rs.next()?new CopyView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "game_id"),rs.getString("name"),
                rs.getString("copy_code"),rs.getString("condition")):null,dbUuid(copyId));
    }

    private void recordCondition(UUID copyId, UUID loanId, UUID actorId,
                                 String before, String after, String reason) {
        jdbc.update("""
            INSERT INTO physical_game_copy_condition_events
                (copy_id,loan_id,actor_id,before_condition,after_condition,reason)
            VALUES (?,?,?,?,?,?)
            """,dbUuid(copyId),dbUuid(loanId),dbUuid(actorId),before,after,reason);
    }

    /** An OPEN loss report must be reviewed before a routine handover/return
     * can complete, preventing stale incident allegations after stock release.
     */
    private void requireNoPendingLossReport(UUID loanId) {
        Boolean open=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_game_loss_incidents
                WHERE loan_id=? AND status='OPEN')
            """,Boolean.class,dbUuid(loanId));
        if (Boolean.TRUE.equals(open))
            throw conflict("Manager must review the pending loss report first");
    }

    private String requiredReason(String value) {
        String note=value==null?"":value.strip();
        if (note.length()<5 || note.length()>300
                || note.chars().anyMatch(Character::isISOControl))
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",
                "Inspection reason must contain 5-300 printable characters");
        return note;
    }

    @Transactional
    public LoanView cancel(UUID loanId, StaffPrincipal staff, String requestId) {
        Loan loan=lockLoanByVisit(loanId,staff);
        if (!loan.status().equals("REQUESTED")) throw conflict("Only unassigned requests may be cancelled");
        jdbc.update("UPDATE physical_game_loans SET status='CANCELLED' WHERE id=?",dbUuid(loanId));
        events.gameLoan(loanId,"GAME_LOAN_CANCELLED");
        audit.staff(staff.staffId(),"GAME_REQUEST_CANCELLED","GAME_LOAN",loanId,requestId);
        return one(loanId);
    }

    @Transactional
    public GameView addGame(String name, String description, StaffPrincipal staff, String requestId) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO physical_games(id,name,description) VALUES (?,?,?)",
            dbUuid(id),name.strip(),description==null?null:description.strip());
        audit.staff(staff.staffId(),"GAME_CATALOG_CREATED","PHYSICAL_GAME",id,requestId);
        events.gameInventory(id);
        return new GameView(id,name.strip(),description==null?null:description.strip(),0);
    }

    @Transactional
    public UUID addCopy(UUID gameId, String code, StaffPrincipal staff, String requestId) {
        Boolean exists=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM physical_games WHERE id=? AND active=true)
            """,Boolean.class,dbUuid(gameId));
        if (!Boolean.TRUE.equals(exists)) throw missing();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO physical_game_copies(id,game_id,copy_code) VALUES (?,?,?)
            """,dbUuid(id),dbUuid(gameId),code.strip());
        audit.staff(staff.staffId(),"GAME_COPY_REGISTERED","PHYSICAL_GAME_COPY",id,requestId);
        events.gameInventory(gameId);
        return id;
    }

    private void requireActiveVisitExclusive(UUID visitId) {
        String state=jdbc.query("""
            SELECT status FROM table_visits WHERE id=? FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null,dbUuid(visitId));
        if (!"ACTIVE".equals(state)) throw conflict("Table visit is no longer active");
    }

    private void requireActiveVisit(UUID visitId) {
        String state=jdbc.query("""
            SELECT status FROM table_visits WHERE id=? FOR SHARE
            """,rs->rs.next()?rs.getString(1):null,dbUuid(visitId));
        if (!"ACTIVE".equals(state)) throw conflict("Table visit is no longer active");
    }

    private Loan lockLoanByVisit(UUID loanId, StaffPrincipal staff) {
        UUID visitId=jdbc.query("""
            SELECT visit_id FROM physical_game_loans WHERE id=?
            """,rs->rs.next()?com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"visit_id"):null,
            dbUuid(loanId));
        if (visitId==null) throw missing();
        requireActiveVisit(visitId);
        if (!supervisor(staff)) {
            Boolean mine=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM table_visits WHERE id=? AND opened_by=?)
                """,Boolean.class,dbUuid(visitId),dbUuid(staff.staffId()));
            if (!Boolean.TRUE.equals(mine)) throw new ApiFailureException(
                HttpStatus.FORBIDDEN,"FORBIDDEN","Waiter is not responsible for this visit");
        }
        String lockClause=com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)
            == com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? " FOR UPDATE" : " FOR UPDATE OF l";
        return jdbc.query("""
            SELECT l.id,l.visit_id,l.guest_id,l.game_id,l.copy_id,l.status,c.copy_code
            FROM physical_game_loans l
            LEFT JOIN physical_game_copies c ON c.id=l.copy_id
            WHERE l.id=?
            """+lockClause,rs->rs.next()?new Loan(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"visit_id"),rs.getString("status"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"game_id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"copy_id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"guest_id"),
                rs.getString("copy_code")):null,dbUuid(loanId));
    }

    private LoanView one(UUID loanId) {
        return jdbc.query("""
            SELECT l.id,l.game_id,g.name,l.status,t.table_code,c.copy_code,l.requested_at
            FROM physical_game_loans l JOIN physical_games g ON g.id=l.game_id
            JOIN table_visits v ON v.id=l.visit_id JOIN cafe_tables t ON t.id=v.table_id
            LEFT JOIN physical_game_copies c ON c.id=l.copy_id
            WHERE l.id=?
            """,rs->rs.next()?map(rs):null,dbUuid(loanId));
    }
    private LoanView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LoanView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "game_id"),
            rs.getString("name"),rs.getString("status"),rs.getString("table_code"),
            rs.getString("copy_code"),
            com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(rs.getObject("requested_at")));
    }
    private Object dbUuid(UUID value) {
        return com.natsx.smartcafe.common.db.JdbcUuid.parameter(
            value,com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc));
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
