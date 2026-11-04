package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Isolated MySQL core-schema parity tests; never shares a PG service/database.
 * The MySQL runtime remains fail-closed until ALL 31 migrations are ported.
 */
@Testcontainers
class MySqlCoreParityIntegrationTest {
    @Container
    static final MySQLContainer mysql =
        new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            // MySQL binlog requires SUPER or trusted-function-creators for
            // CREATE TRIGGER. Docker CI can set this explicitly; managed
            // hosts MUST prove equivalent capability before any deployment.
            .withCommand("--log-bin-trust-function-creators=1")
            .withDatabaseName("smart_cafe_mysql_test")
            .withUsername("cafe_test")
            .withPassword("test-only-local-mysql-pass");

    static JdbcTemplate jdbc;
    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        // A real staged upgrade, not a Flyway baseline shortcut:
        // first deploy V1-V14, then migrate that same disposable database
        // to V31. Both stages execute all scripts, retaining history.
        // This covers a fresh database and a forward-only upgrade path.
        var initial = Flyway.configure().dataSource(ds)
            .locations("classpath:db/mysql")
            .target("14")
            .load();
        assertEquals(14, initial.migrate().migrationsExecuted);

        var upgraded = Flyway.configure().dataSource(ds)
            .locations("classpath:db/mysql")
            .load();
        assertEquals(17, upgraded.migrate().migrationsExecuted);
        // Applying the current head a second time must be a no-op.
        assertEquals(0, upgraded.migrate().migrationsExecuted);
        jdbc = new JdbcTemplate(ds);
        dataSource = ds;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String newStaff() {
        String user = id();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            user, ("test-" + user).substring(0, 40), "dummy-hash-not-for-login");
        return user;
    }

    private static String newTable() {
        String table = id();
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table, "T" + table.substring(0, 8), "mysql-" + table, 4);
        return table;
    }

    private static String newVisit(String table, String staff) {
        String visit = id();
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """, visit, table, staff, "a".repeat(64));
        return visit;
    }

    @Test
    void activeTableConstraintIsEquivalentToPostgresPartialUnique() {
        String staff = newStaff();
        String table = newTable();
        String first = newVisit(table, staff);
        assertThrows(DataAccessException.class, () -> newVisit(table, staff));
        jdbc.update("""
            UPDATE table_visits SET status='CLOSED',closed_at=CURRENT_TIMESTAMP(6)
            WHERE id=?
            """, first);
        newVisit(table, staff);
    }

    @Test
    void mySqlGameLossReportNeedsManagerDecisionAndDoesNotEraseEvidence() {
        String waiter = newStaff();
        String manager = newStaff();
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            waiter,"WAITER");
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            manager,"MANAGER");
        String visit = newVisit(newTable(),waiter);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic game incident customer");
        String game = id();
        String copy = id();
        String serial = "GAME-LOST-"+copy;
        jdbc.update("INSERT INTO physical_games(id,name) VALUES (?,?)",
            game,"Incident game "+game);
        jdbc.update("INSERT INTO physical_game_copies(id,game_id,copy_code) VALUES (?,?,?)",
            copy,game,serial);

        var waiterIdentity=new com.natsx.smartcafe.identity.StaffPrincipal(
            UUID.fromString(waiter),"synthetic-waiter","synthetic",java.util.Set.of("WAITER"));
        var managerIdentity=new com.natsx.smartcafe.identity.StaffPrincipal(
            UUID.fromString(manager),"synthetic-manager","synthetic",java.util.Set.of("MANAGER"));
        var guestIdentity=new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest),UUID.fromString(visit),"synthetic-session");
        var audit=new com.natsx.smartcafe.audit.AuditTrail(jdbc);
        var events=new com.natsx.smartcafe.common.events.OutboxEvents(jdbc);
        var gameLoans=new com.natsx.smartcafe.games.GameLoanService(jdbc,audit,events);
        var incidents=new com.natsx.smartcafe.games.GameLossIncidentService(jdbc,audit,events);
        var tx=new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        var requested=tx.execute(status->gameLoans.request(guestIdentity,UUID.fromString(game)));
        tx.executeWithoutResult(status->gameLoans.assign(requested.id(),waiterIdentity,"loss-assign"));
        tx.executeWithoutResult(status->gameLoans.handover(requested.id(),serial,waiterIdentity,"loss-handover"));

        var reported=tx.execute(status->incidents.report(requested.id(),
            "Unit missing during table service",waiterIdentity,"report-physical-loss"));
        assertEquals("OPEN",reported.status());
        assertEquals(1L,incidents.openIncidentSummary().openCount());
        assertEquals(1,incidents.reviewQueue().size());
        assertEquals(reported.id(),incidents.incidentEvidence(reported.id()).incident().id());
        assertEquals("GAME_LOSS_REPORTED",
            incidents.incidentEvidence(reported.id()).activity().getFirst().operation());

        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->incidents.report(requested.id(),
                "Duplicate report should be denied",waiterIdentity,"duplicate-loss")));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->incidents.review(reported.id(),"CONFIRM_LOST",serial,
                "Unauthorized review attempt",waiterIdentity,"forbidden-manager-action")));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->incidents.review(reported.id(),"CONFIRM_LOST","WRONG-CODE",
                "Incorrect physical inventory check",managerIdentity,"bad-code")));

        var confirmed=tx.execute(status->incidents.review(reported.id(),
            "CONFIRM_LOST",serial,"Manager verified unit is lost",
            managerIdentity,"manager-confirmed-loss"));
        assertEquals("CONFIRMED_LOST",confirmed.status());
        assertEquals("LOST",jdbc.queryForObject(
            "SELECT status FROM physical_game_loans WHERE id=?",String.class,requested.id().toString()));
        assertEquals("LOST",jdbc.queryForObject(
            "SELECT c.condition FROM physical_game_copies c WHERE c.id=?",String.class,copy));
        assertEquals(0,gameLoans.catalog().stream()
            .filter(g->g.id().equals(UUID.fromString(game))).findFirst()
            .orElseThrow().availableCopies());
        assertEquals(0L,incidents.openIncidentSummary().openCount());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->incidents.review(reported.id(),"DISMISS",serial,
                "Cannot reverse completed manager decision",managerIdentity,"illegal-replay")));

        var found=tx.execute(status->gameLoans.recoverLostCopy(UUID.fromString(copy),serial,
            "Physical unit located after incident",managerIdentity,"found-and-quarantine"));
        assertEquals("DAMAGED",found.condition());
        assertEquals("CONFIRMED_LOST",
            incidents.incidentEvidence(reported.id()).incident().status());
        assertEquals("LOST",jdbc.queryForObject(
            "SELECT status FROM physical_game_loans WHERE id=?",String.class,requested.id().toString()));
        assertEquals(1,jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs
            WHERE resource_type='GAME_LOSS_INCIDENT' AND resource_id=?
              AND operation='GAME_LOSS_CONFIRMED'
            """,Integer.class,reported.id().toString()));
    }

    @Test
    void mySqlPhysicalGameRequestAssignHandoverAndReturnNeverDoubleLoansCopy() {
        String waiter1 = newStaff();
        String waiter2 = newStaff();
        String visit1 = newVisit(newTable(), waiter1);
        String visit2 = newVisit(newTable(), waiter2);
        String guest1 = id();
        String guest2 = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest1,visit1,"Game borrowing guest");
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest2,visit2,"Second borrowing guest");
        String game=id();
        String copy=id();
        String code="SC-GAME-"+copy;
        jdbc.update("INSERT INTO physical_games(id,name) VALUES (?,?)",
            game,"Synthetic game "+game);
        jdbc.update("""
            INSERT INTO physical_game_copies(id,game_id,copy_code)
            VALUES (?,?,?)
            """,copy,game,code);

        var principal1=new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest1),UUID.fromString(visit1),"synthetic-guest-token-1");
        var principal2=new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest2),UUID.fromString(visit2),"synthetic-guest-token-2");
        var staff1=new com.natsx.smartcafe.identity.StaffPrincipal(
            UUID.fromString(waiter1),"synthetic-waiter-1","token-1",java.util.Set.of("WAITER"));
        var staff2=new com.natsx.smartcafe.identity.StaffPrincipal(
            UUID.fromString(waiter2),"synthetic-waiter-2","token-2",java.util.Set.of("WAITER"));
        var events=new com.natsx.smartcafe.common.events.OutboxEvents(jdbc);
        var service=new com.natsx.smartcafe.games.GameLoanService(
            jdbc,new com.natsx.smartcafe.audit.AuditTrail(jdbc),events);
        var tx=new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        assertEquals(1,service.catalog().stream().filter(g->g.id().equals(UUID.fromString(game)))
            .findFirst().orElseThrow().availableCopies());

        var first=tx.execute(status->service.request(principal1,UUID.fromString(game)));
        var second=tx.execute(status->service.request(principal2,UUID.fromString(game)));
        assertEquals("REQUESTED",first.status());
        assertEquals("REQUESTED",second.status());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->service.request(principal1,UUID.fromString(game))));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->service.assign(first.id(),staff2,"wrong-waiter")));

        var reserved=tx.execute(status->service.assign(first.id(),staff1,"assign-unit"));
        assertEquals("ASSIGNED",reserved.status());
        assertEquals(code,reserved.copyCode());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->service.assign(second.id(),staff2,"double-assign")));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->service.handover(first.id(),"WRONG-CODE",staff1,"bad-handover")));

        var handed=tx.execute(status->service.handover(first.id(),code,staff1,"handover-unit"));
        assertEquals("IN_USE",handed.status());
        assertEquals(code,handed.copyCode());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->service.requestReturn(principal2,first.id())));
        var pending=tx.execute(status->service.requestReturn(principal1,first.id()));
        assertEquals("RETURN_PENDING",pending.status());
        assertEquals("RETURN_PENDING",tx.execute(status->
            service.requestReturn(principal1,first.id())).status());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->service.assign(second.id(),staff2,"copy-still-out")));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            ()->tx.execute(status->service.returnCopy(first.id(),"BAD-SERIAL",staff1,"bad-return")));

        var returned=tx.execute(status->service.returnCopy(
            first.id(),code,staff1,"verified-return"));
        assertEquals("RETURNED",returned.status());
        assertEquals(1,service.catalog().stream().filter(g->g.id().equals(UUID.fromString(game)))
            .findFirst().orElseThrow().availableCopies());
        var secondAssigned=tx.execute(status->service.assign(second.id(),staff2,"assign-second"));
        assertEquals("ASSIGNED",secondAssigned.status());
        assertEquals(code,secondAssigned.copyCode());
        tx.executeWithoutResult(status->service.handover(second.id(),code,staff2,"handover-second"));
        tx.executeWithoutResult(status->service.returnCopy(second.id(),code,staff2,"return-second"));
        assertEquals(0,jdbc.queryForObject("""
            SELECT COUNT(*) FROM physical_game_loans
            WHERE copy_id=? AND status IN ('ASSIGNED','IN_USE','RETURN_PENDING')
            """,Integer.class,copy));
        assertEquals(1,jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE aggregate_type='GAME_LOAN' AND aggregate_id=?
              AND event_type='GAME_LOAN_RETURNED'
            """,Integer.class,first.id().toString()));
    }

    @Test
    void mySqlWaiterCashTenderNeedsOpenTillAndIssuesOnePaidReceipt() {
        String waiter = newStaff();
        String cashier = newStaff();
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            waiter, "WAITER");
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            cashier, "CASHIER");
        String visit = newVisit(newTable(), waiter);
        String guest = id();
        String category = id();
        String menu = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic cash customer");
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category,"Cash test","mysql-cash-"+category);
        jdbc.update("INSERT INTO menu_items(id,category_id,name,base_price_idr) VALUES (?,?,?,?)",
            menu,category,"Cash meal",16000L);

        var principal = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest),UUID.fromString(visit),"synthetic-cash-session");
        var variants = new com.natsx.smartcafe.catalog.VariantPricingService(jdbc);
        var outbox = new com.natsx.smartcafe.common.events.OutboxEvents(jdbc);
        var receipts = new com.natsx.smartcafe.receipts.GuestReceiptService(jdbc);
        var cart = new com.natsx.smartcafe.orders.CartService(jdbc,variants);
        var checkout = new com.natsx.smartcafe.orders.CashCheckoutService(
            jdbc,cart,outbox,variants,false,"disabled");
        var ops = new com.natsx.smartcafe.orders.CashWorkflowService(
            jdbc,new com.natsx.smartcafe.audit.AuditTrail(jdbc),outbox,receipts);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        var order = tx.execute(status -> checkout.checkout(principal,
            java.util.List.of(new com.natsx.smartcafe.orders.CashCheckoutService.Line(
                UUID.fromString(menu),1)),"cash-tender-"+UUID.randomUUID().toString().replace("-","")));
        UUID orderId = order.id();
        UUID waiterId = UUID.fromString(waiter);
        tx.executeWithoutResult(status -> ops.approve(orderId,waiterId,"cash-approve"));
        UUID ticketId=jdbc.queryForObject(
            "SELECT id FROM kitchen_tickets WHERE order_id=?",
            (rs,n)->com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
            orderId.toString());

        UUID intentId=jdbc.queryForObject(
            "SELECT id FROM payment_intents WHERE order_id=?",
            (rs,n)->com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
            orderId.toString());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> ops.capture(
                intentId,20000L,"waiter-cash-0001",waiterId,"cash-premature")));

        tx.executeWithoutResult(status -> ops.kitchenStart(ticketId,waiterId,"cash-start"));
        tx.executeWithoutResult(status -> ops.kitchenReady(ticketId,waiterId,"cash-ready"));
        tx.executeWithoutResult(status -> ops.served(orderId,waiterId,"cash-served"));

        // MySQL requires an active till row as a transactional lock.
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> ops.capture(
                intentId,20000L,"waiter-cash-0001",waiterId,"cash-no-shift")));

        var drawer = new com.natsx.smartcafe.ops.CashDrawerShiftService(jdbc);
        var cashierActor = new com.natsx.smartcafe.identity.StaffPrincipal(
            UUID.fromString(cashier),"synthetic-cashier","synthetic-token",
            java.util.Set.of("CASHIER"));
        var waiterActor = new com.natsx.smartcafe.identity.StaffPrincipal(
            waiterId,"synthetic-waiter","synthetic-token",java.util.Set.of("WAITER"));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> drawer.open(
                waiterActor,50000L,"waiter-open-denied","bad-role")));
        String shiftOpenKey="open-"+id();
        var opened=tx.execute(status -> drawer.open(
            cashierActor,50000L,shiftOpenKey,"cash-shift-open"));
        assertEquals("OPEN",opened.state());
        var retryOpen=tx.execute(status -> drawer.open(
            cashierActor,50000L,shiftOpenKey,"cash-shift-replay"));
        assertEquals(opened.id(),retryOpen.id());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> drawer.open(
                cashierActor,50000L,"second-"+id(),"cash-duplicate-till")));

        var paid=tx.execute(status -> ops.capture(
            intentId,20000L,"waiter-cash-0001",waiterId,"cash-collected"));
        assertEquals("PAID",paid.paymentStatus());
        assertEquals(20000L,paid.receivedIdr());
        assertEquals(4000L,paid.changeGivenIdr());
        org.junit.jupiter.api.Assertions.assertNotNull(paid.receiptId());

        var replay=tx.execute(status -> ops.capture(
            intentId,20000L,"waiter-cash-0001",waiterId,"cash-replay"));
        assertEquals(paid.receiptId(),replay.receiptId());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> ops.capture(
                intentId,21000L,"waiter-cash-0001",waiterId,"cash-changed-amount")));
        assertEquals(1,jdbc.queryForObject(
            "SELECT COUNT(*) FROM cash_tenders WHERE payment_intent_id=?",
            Integer.class,intentId.toString()));
        assertEquals(1,jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipts WHERE order_id=?",
            Integer.class,orderId.toString()));
        assertEquals("PAID",jdbc.queryForObject(
            "SELECT aggregate_status FROM payment_intents WHERE id=?",
            String.class,intentId.toString()));
        var ownReceipt=receipts.ownReceiptForOrder(orderId,principal);
        assertEquals(paid.receiptId(),ownReceipt.id());
        assertEquals(16000L,ownReceipt.totalIdr());
        UUID tenderId=jdbc.queryForObject(
            "SELECT id FROM cash_tenders WHERE payment_intent_id=?",
            (rs,n)->com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
            intentId.toString());
        assertEquals(1,jdbc.queryForObject("""
            SELECT COUNT(*) FROM cash_tender_handoffs WHERE tender_id=?
              AND cash_shift_id=? AND waiter_staff_id=? AND received_at IS NULL
            """,Integer.class,tenderId.toString(),opened.id().toString(),waiter));
        var pending=drawer.dashboard();
        assertEquals(opened.id(),pending.active().id());
        assertEquals(1L,pending.pendingHandoffCount());
        assertEquals(16000L,pending.pendingHandoffs().getFirst().netCashIdr());

        String shiftCloseKey="close-"+id();
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> drawer.close(opened.id(),cashierActor,66000L,
                shiftCloseKey,"Cash drawer reconciliation complete","close-pending")));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> drawer.receive(opened.id(),tenderId,cashierActor,
                15000L,"ack-"+id(),"wrong-count")));
        String handoffKey="ack-"+id();
        var received=tx.execute(status -> drawer.receive(
            opened.id(),tenderId,cashierActor,16000L,handoffKey,"receive-waiter-cash"));
        assertEquals("RECEIVED",received.state());
        assertEquals(16000L,received.netCashIdr());
        assertEquals("RECEIVED",tx.execute(status -> drawer.receive(
            opened.id(),tenderId,cashierActor,16000L,handoffKey,"replay-handoff")).state());

        var closed=tx.execute(status -> drawer.close(
            opened.id(),cashierActor,66000L,shiftCloseKey,
            "Cash drawer reconciliation complete","close-cash-drawer"));
        assertEquals("CLOSED",closed.state());
        assertEquals(66000L,closed.expectedDrawerIdr());
        assertEquals(16000L,closed.recordedCashIdr());
        assertEquals(0L,closed.differenceIdr());
        assertEquals("CLOSED",tx.execute(status -> drawer.close(
            opened.id(),cashierActor,66000L,shiftCloseKey,
            "Cash drawer reconciliation complete","replay-cash-close")).state());
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM cash_drawer_shifts WHERE closed_at IS NULL",
            Integer.class));
    }

    @Test
    void mySqlCashReceiptIsIssuedOnlyAfterPaidAndOwnedByGuest() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        String otherGuest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Receipt customer");
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            otherGuest, visit, "Other customer");
        String order = id();
        String intent = id();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,?,?,?)
            """,order,visit,guest,14000L,14000L,"synthetic-receipt-test");
        jdbc.update("""
            INSERT INTO order_lines(order_id,name_snapshot,variant_snapshot,unit_price_idr,quantity,line_total_idr)
            VALUES (?, ?,JSON_OBJECT('label',?),?,?,?)
            """, order, "Receipt drink", "Oat \"milk\"",14000L,1,14000L);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?, 'CASH',?, 'AWAITING_CASH')
            """,intent,order,14000L);

        var receipts = new com.natsx.smartcafe.receipts.GuestReceiptService(jdbc);
        var owner = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest), UUID.fromString(visit), "synthetic-owner-hash");
        var stranger = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(otherGuest), UUID.fromString(visit), "synthetic-foreign-hash");

        assertThrows(IllegalStateException.class, () -> receipts.issueCash(
            UUID.fromString(intent),UUID.fromString(order),14000L));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> receipts.ownReceiptForOrder(UUID.fromString(order),owner));
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipts WHERE order_id=?",Integer.class,order));

        // Synthetic settlement is performed directly here only to test
        // read/issue guards, NOT to certify staff tender workflow.
        jdbc.update("""
            UPDATE payment_intents SET aggregate_status='PAID',amount_paid_idr=14000
            WHERE id=?
            """,intent);
        UUID receiptId = receipts.issueCash(
            UUID.fromString(intent),UUID.fromString(order),14000L);
        var result = receipts.ownReceiptForOrder(UUID.fromString(order),owner);
        assertEquals(receiptId,result.id());
        assertEquals(UUID.fromString(order),result.orderId());
        assertEquals(14000L,result.totalIdr());
        assertEquals("IDR",result.currency());
        org.junit.jupiter.api.Assertions.assertNotNull(result.issuedAt());
        assertEquals(1,result.lines().size());
        assertEquals("Oat \"milk\"",result.lines().getFirst().variantLabel());

        assertEquals(1,jdbc.queryForObject("""
            SELECT COUNT(*) FROM receipts
            WHERE id=? AND JSON_TYPE(JSON_EXTRACT(document_json,'$.lines'))='ARRAY'
              AND JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.lines[0].variant.label'))=?
            """,Integer.class,receiptId.toString(),"Oat \"milk\""));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> receipts.ownReceiptForOrder(UUID.fromString(order),stranger));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> receipts.ownReceipt(receiptId,stranger));
        assertThrows(org.springframework.dao.DataAccessException.class,
            () -> receipts.issueCash(UUID.fromString(intent),UUID.fromString(order),14000L));
        assertThrows(org.springframework.dao.DataAccessException.class,() ->
            jdbc.update("UPDATE receipts SET total_idr=1 WHERE id=?",receiptId.toString()));
    }

    @Test
    void mySqlOccKitchenAndWaiterAdvanceOrderWithoutMarkingCashPaid() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        String category = id();
        String menu = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Operations synthetic customer");
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category,"Operation test","mysql-ops-"+category);
        jdbc.update("INSERT INTO menu_items(id,category_id,name,base_price_idr) VALUES (?,?,?,?)",
            menu,category,"Kitchen menu",17000L);

        var principal = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest),UUID.fromString(visit),"synthetic-ops-session");
        var variants = new com.natsx.smartcafe.catalog.VariantPricingService(jdbc);
        var outbox = new com.natsx.smartcafe.common.events.OutboxEvents(jdbc);
        var carts = new com.natsx.smartcafe.orders.CartService(jdbc,variants);
        var checkout = new com.natsx.smartcafe.orders.CashCheckoutService(
            jdbc,carts,outbox,variants,false,"disabled");
        var ops = new com.natsx.smartcafe.orders.CashWorkflowService(
            jdbc,new com.natsx.smartcafe.audit.AuditTrail(jdbc),outbox,
            new com.natsx.smartcafe.receipts.GuestReceiptService(jdbc));
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        var order = tx.execute(status -> checkout.checkout(principal,
            java.util.List.of(new com.natsx.smartcafe.orders.CashCheckoutService.Line(
                UUID.fromString(menu),1)),"mysql-ops-checkout"));
        tx.executeWithoutResult(status -> ops.approve(
            order.id(),UUID.fromString(staff),"ops-approve"));
        UUID ticket = jdbc.queryForObject(
            "SELECT id FROM kitchen_tickets WHERE order_id=?",
            (rs,n) -> com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
            order.id().toString());
        var kitchenTicket = ops.kitchen().stream()
            .filter(k -> k.id().equals(ticket)).findFirst().orElseThrow();
        assertEquals("ACCEPTED",kitchenTicket.status());
        assertEquals("Kitchen menu",kitchenTicket.lines().getFirst().name());

        tx.executeWithoutResult(status -> ops.kitchenStart(
            ticket,UUID.fromString(staff),"ops-start"));
        tx.executeWithoutResult(status -> ops.kitchenReady(
            ticket,UUID.fromString(staff),"ops-ready"));

        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.executeWithoutResult(status ->
                ops.served(order.id(),UUID.randomUUID(),"unauthorized-serve")));
        tx.executeWithoutResult(status -> ops.served(
            order.id(),UUID.fromString(staff),"ops-served"));

        assertEquals("SERVED", jdbc.queryForObject(
            "SELECT status FROM orders WHERE id=?",String.class,order.id().toString()));
        assertEquals("AWAITING_CASH", jdbc.queryForObject("""
            SELECT aggregate_status FROM payment_intents WHERE order_id=?
            """,String.class,order.id().toString()));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipts WHERE order_id=?",
            Integer.class,order.id().toString()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs
            WHERE operation='ORDER_SERVED' AND resource_id=?
            """,Integer.class,order.id().toString()));
    }

    @Test
    void mySqlOccRejectRestoresAllocatedStockAndKeepsPaymentUnpaid() {
        String staff = newStaff();
        String visit = newVisit(newTable(),staff);
        String guest = id();
        String category = id();
        String menu = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Rejected synthetic customer");
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category,"Rejection test","mysql-reject-"+category);
        jdbc.update("INSERT INTO menu_items(id,category_id,name,base_price_idr) VALUES (?,?,?,?)",
            menu,category,"Limited coffee",13000L);
        jdbc.update("UPDATE item_availability SET limited_quantity=3 WHERE menu_item_id=?",menu);

        var principal = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest),UUID.fromString(visit),"synthetic-reject-session");
        var variants = new com.natsx.smartcafe.catalog.VariantPricingService(jdbc);
        var outbox = new com.natsx.smartcafe.common.events.OutboxEvents(jdbc);
        var checkout = new com.natsx.smartcafe.orders.CashCheckoutService(
            jdbc,new com.natsx.smartcafe.orders.CartService(jdbc,variants),
            outbox,variants,false,"disabled");
        var ops = new com.natsx.smartcafe.orders.CashWorkflowService(
            jdbc,new com.natsx.smartcafe.audit.AuditTrail(jdbc),outbox,
            new com.natsx.smartcafe.receipts.GuestReceiptService(jdbc));
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        var order = tx.execute(status -> checkout.checkout(principal,
            java.util.List.of(new com.natsx.smartcafe.orders.CashCheckoutService.Line(
                UUID.fromString(menu),2)),"mysql-reject-once"));
        assertEquals(1L,jdbc.queryForObject(
            "SELECT limited_quantity FROM item_availability WHERE menu_item_id=?",
            Long.class,menu));
        tx.executeWithoutResult(status -> ops.reject(order.id(),
            UUID.fromString(staff),"Customer changed their order","ops-reject"));
        assertEquals(3L,jdbc.queryForObject(
            "SELECT limited_quantity FROM item_availability WHERE menu_item_id=?",
            Long.class,menu));
        assertEquals("REJECTED",jdbc.queryForObject(
            "SELECT status FROM orders WHERE id=?",String.class,order.id().toString()));
        assertEquals("UNPAID",jdbc.queryForObject(
            "SELECT aggregate_status FROM payment_intents WHERE order_id=?",
            String.class,order.id().toString()));
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.executeWithoutResult(status -> ops.reject(order.id(),
                UUID.fromString(staff),"Reject again blocked","ops-replay")));
    }

    @Test
    void mySqlCashCheckoutFromCartChargesOnceAndKeepsPaymentUnpaid() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Test checkout customer");
        String category = id();
        String menu = id();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category, "Checkout category", "mysql-checkout-" + category);
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr)
            VALUES (?,?,?,?)
            """,menu,category,"Synthetic coffee",12500L);
        jdbc.update("UPDATE item_availability SET limited_quantity=2 WHERE menu_item_id=?",menu);

        var principal = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest), UUID.fromString(visit), "synthetic-session");
        var variantPricing = new com.natsx.smartcafe.catalog.VariantPricingService(jdbc);
        var cart = new com.natsx.smartcafe.orders.CartService(jdbc,variantPricing);
        var outbox = new com.natsx.smartcafe.common.events.OutboxEvents(jdbc);
        var checkout = new com.natsx.smartcafe.orders.CashCheckoutService(
            jdbc,cart,outbox,variantPricing,false,"disabled");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        var put = tx.execute(status -> cart.replace(principal,0L,
            java.util.List.of(new com.natsx.smartcafe.orders.CashCheckoutService.Line(
                UUID.fromString(menu),2))));
        assertEquals(1L, put.version());
        var first = tx.execute(status ->
            checkout.checkoutFromCart(principal,1L,"mysql-cash-unique-key"));
        assertEquals("SUBMITTED", first.status());
        assertEquals("AWAITING_CASH", first.paymentStatus());
        assertEquals(25000L, first.totalIdr());
        var replay = tx.execute(status ->
            checkout.checkoutFromCart(principal,1L,"mysql-cash-unique-key"));
        assertEquals(first.id(), replay.id());
        assertEquals(first.totalIdr(), replay.totalIdr());

        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE guest_id=?",Integer.class,guest));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM payment_intents
            WHERE order_id=? AND aggregate_status='AWAITING_CASH'
              AND amount_due_idr=25000 AND amount_paid_idr=0
            """,Integer.class, first.id().toString()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM order_lines WHERE order_id=?
              AND JSON_TYPE(variant_snapshot)='OBJECT'
              AND JSON_LENGTH(variant_snapshot)=0
            """,Integer.class,first.id().toString()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=?
              AND aggregate_type='ORDER' AND event_type='ORDER_SUBMITTED'
            """,Integer.class,first.id().toString()));
        assertEquals(0L, jdbc.queryForObject(
            "SELECT limited_quantity FROM item_availability WHERE menu_item_id=?",
            Long.class,menu));
        assertEquals(2L, tx.execute(status -> cart.get(principal)).version());
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status ->
                checkout.checkoutFromCart(principal,2L,"mysql-cash-unique-key")));
    }

    @Test
    void mySqlModifierSnapshotUsesEscapedBoundJsonAndServerPrice() {
        String staff = newStaff();
        String visit = newVisit(newTable(),staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic modifier customer");
        String category=id();
        String menu=id();
        String group=id();
        String option=id();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category,"Modifiers","mysql-modifier-"+category);
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr) VALUES (?,?,?,?)
            """,menu,category,"Coffee with modifiers",12000L);
        jdbc.update("""
            INSERT INTO menu_variant_groups(id,menu_item_id,name,required)
            VALUES (?,?,?,true)
            """,group,menu,"Milk");
        jdbc.update("""
            INSERT INTO menu_variant_options(id,group_id,option_key,label,price_delta_idr)
            VALUES (?,?,?,?,?)
            """,option,group,"OAT","Oat \"milk\"",1500L);

        var principal = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest),UUID.fromString(visit),"synthetic-session");
        var variants = new com.natsx.smartcafe.catalog.VariantPricingService(jdbc);
        var carts = new com.natsx.smartcafe.orders.CartService(jdbc,variants);
        var checkout = new com.natsx.smartcafe.orders.CashCheckoutService(
            jdbc,carts,new com.natsx.smartcafe.common.events.OutboxEvents(jdbc),
            variants,false,"disabled");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var result = tx.execute(status -> checkout.checkout(principal,
            java.util.List.of(new com.natsx.smartcafe.orders.CashCheckoutService.Line(
                UUID.fromString(menu),1,UUID.fromString(option))),
            "mysql-modifier-snapshot"));
        assertEquals(13500L,result.totalIdr());
        assertEquals("AWAITING_CASH",result.paymentStatus());
        assertEquals("Oat \"milk\"",jdbc.queryForObject("""
            SELECT JSON_UNQUOTE(JSON_EXTRACT(variant_snapshot,'$.options[0].label'))
            FROM order_lines WHERE order_id=?
            """,String.class,result.id().toString()));
        assertEquals(option,jdbc.queryForObject("""
            SELECT JSON_UNQUOTE(JSON_EXTRACT(variant_snapshot,'$.options[0].id'))
            FROM order_lines WHERE order_id=?
            """,String.class,result.id().toString()));
    }

    @Test
    void outboxOnMySqlStoresOnlySafeEmptyJsonWithCanonicalUuidKeys() {
        var events = new com.natsx.smartcafe.common.events.OutboxEvents(jdbc);
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        UUID order = UUID.randomUUID();
        UUID game = UUID.randomUUID();

        events.order(order, "ORDER_SUBMITTED");
        events.tableVisit(UUID.fromString(visit), "TABLE_VISIT_OPENED");
        events.gameInventory(game);

        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE aggregate_type='ORDER' AND aggregate_id=?
              AND event_type='ORDER_SUBMITTED'
              AND JSON_TYPE(payload_safe)='OBJECT'
              AND JSON_LENGTH(payload_safe)=0
            """, Integer.class, order.toString()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE aggregate_type='TABLE_VISIT' AND aggregate_id=?
              AND event_type='TABLE_VISIT_OPENED'
              AND JSON_TYPE(payload_safe)='OBJECT'
              AND JSON_LENGTH(payload_safe)=0
            """, Integer.class, visit));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE aggregate_type='GAME_INVENTORY' AND aggregate_id=?
              AND JSON_TYPE(payload_safe)='OBJECT'
              AND JSON_LENGTH(payload_safe)=0
            """, Integer.class, game.toString()));

        // A malformed event must not bypass the domain's allowlist.
        assertThrows(IllegalArgumentException.class,
            () -> events.tableVisit(UUID.fromString(visit), "INVALID_EVENT"));
    }

    @Test
    void checkoutIdempotencyLockIsTransactionScopedAndVisitScopedOnMySql() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic checkout guest");
        var guestId = UUID.fromString(guest);
        var visitId = UUID.fromString(visit);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        // A FOR UPDATE lock in autocommit would be released prematurely.
        assertThrows(IllegalStateException.class, () ->
            com.natsx.smartcafe.common.db.JdbcSqlDialect.lockGuestCheckout(
                jdbc, guestId, visitId, "idempotency-sample"));

        assertEquals(Boolean.TRUE, tx.execute(status ->
            com.natsx.smartcafe.common.db.JdbcSqlDialect.lockGuestCheckout(
                jdbc, guestId, visitId, "idempotency-sample")));
        assertEquals(Boolean.FALSE, tx.execute(status ->
            com.natsx.smartcafe.common.db.JdbcSqlDialect.lockGuestCheckout(
                jdbc, UUID.randomUUID(), visitId, "forged-member")));
        assertEquals(Boolean.FALSE, tx.execute(status ->
            com.natsx.smartcafe.common.db.JdbcSqlDialect.lockGuestCheckout(
                jdbc, guestId, UUID.randomUUID(), "wrong-visit")));
    }

    @Test
    void guestCartMySqlUsesChar36IdsAndPreservesVersionAndStockGuards() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic cart customer");

        String category = id();
        String menu = id();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category, "Test items", "mysql-cart-" + category);
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr,availability)
            VALUES (?,?,?,?,?)
            """, menu, category, "Menu sample", 12500L, "AVAILABLE");

        var principal = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.fromString(guest), UUID.fromString(visit), "synthetic-token-hash");
        var variants = new com.natsx.smartcafe.catalog.VariantPricingService(jdbc);
        var carts = new com.natsx.smartcafe.orders.CartService(jdbc, variants);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        var initial = tx.execute(status -> carts.get(principal));
        assertEquals(0L, initial.version());
        assertEquals(0, initial.lines().size());

        var line = new com.natsx.smartcafe.orders.CashCheckoutService.Line(
            UUID.fromString(menu), 2);
        var changed = tx.execute(status ->
            carts.replace(principal, 0L, java.util.List.of(line)));
        assertEquals(1L, changed.version());
        assertEquals(25000L, changed.estimatedSubtotalIdr());
        assertEquals(1, changed.lines().size());
        assertEquals(2, changed.lines().getFirst().quantity());

        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> carts.replace(principal, 0L,
                java.util.List.of(line))));

        var locked = tx.execute(status -> carts.lockForCheckout(principal, 1L));
        assertEquals(1, locked.size());
        assertEquals(UUID.fromString(menu), locked.getFirst().menuItemId());
        assertEquals(0, locked.getFirst().selectedOptionIds().size());

        tx.executeWithoutResult(status -> carts.consume(principal, 1L));
        var empty = tx.execute(status -> carts.get(principal));
        assertEquals(2L, empty.version());
        assertEquals(0, empty.lines().size());
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*) FROM cart_lines l
            JOIN carts c ON c.id=l.cart_id WHERE c.guest_id=?
            """, Integer.class, guest));
        assertThrows(IllegalStateException.class, () ->
            tx.executeWithoutResult(status -> carts.consume(principal, 1L)));

        // A guessed guest ID in the same visit is not a member.
        var stranger = new com.natsx.smartcafe.identity.GuestPrincipal(
            UUID.randomUUID(), UUID.fromString(visit), "synthetic-stranger-hash");
        assertThrows(com.natsx.smartcafe.common.api.ApiFailureException.class,
            () -> tx.execute(status -> carts.get(stranger)));
    }

    @Test
    void staffLoginUsesMySqlUuidSessionsRevokesAndPersistsFailedThrottles() {
        var passwords = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        var auth = new com.natsx.smartcafe.identity.StaffAuthService(jdbc, passwords);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        String staffId = newStaff();
        String login = jdbc.queryForObject(
            "SELECT login FROM staff_users WHERE id=?", String.class, staffId);
        jdbc.update("UPDATE staff_users SET password_hash=? WHERE id=?",
            passwords.encode("test-staff-secret-24-bytes"), staffId);
        jdbc.update(
            "INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            staffId, "WAITER");

        var signedIn = tx.execute(status -> auth.login(login, "test-staff-secret-24-bytes"));
        assertEquals(null, signedIn.failure());
        var principal = tx.execute(status -> auth.authenticate(signedIn.token()));
        assertEquals(UUID.fromString(staffId), principal.staffId());
        assertEquals(true, principal.roles().contains("WAITER"));
        tx.executeWithoutResult(status -> auth.logout(principal));
        org.junit.jupiter.api.Assertions.assertNull(
            tx.execute(status -> auth.authenticate(signedIn.token())));

        // Failed passwords are recorded on a separate absent identifier.
        // Returning a denial must commit the throttle counter.
        String absent = "missing-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            assertEquals("UNAUTHENTICATED",
                tx.execute(status -> auth.login(absent, "bad-password")).failure());
        }
        assertEquals("RATE_LIMITED",
            tx.execute(status -> auth.login(absent, "bad-password")).failure());
        assertEquals(5, jdbc.queryForObject(
            "SELECT failed_attempts FROM staff_login_throttles WHERE login_hash=?",
            Integer.class, com.natsx.smartcafe.identity.SecureToken.sha256(absent)));
    }

    @Test
    void guestJoinThrottleRejectsTheTwentyFifthAttemptWithinSameTenMinuteWindow() {
        // Exercise the real service's three HMAC buckets and transaction
        // boundary, not merely the vendor SQL chosen by a unit test.
        var limiter = new com.natsx.smartcafe.tables.GuestJoinThrottle(
            jdbc, "synthetic-test-secret-32-plus-bytes-never-production");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        String alias = "throttle-" + UUID.randomUUID();
        String client = "192.0.2.40";

        for (int i = 0; i < 24; i++) {
            assertEquals(Boolean.TRUE, tx.execute(status -> limiter.allow(alias, client)));
        }
        assertEquals(Boolean.FALSE, tx.execute(status -> limiter.allow(alias, client)));

        // Only hashed buckets may be stored. The three rows should represent
        // peer, table and pair, with no plain IP address or QR alias.
        assertEquals(3, jdbc.queryForObject(
            "SELECT COUNT(*) FROM guest_join_throttles WHERE attempts > 0",
            Integer.class));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM guest_join_throttles WHERE bucket_hash LIKE ?",
            Integer.class, "%192.0.2.40%"));
    }

    @Test
    void guestJoinThrottleMySqlIncrementsResetsExpiredWindowAndPurges() {
        var dialect = com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL;
        var statement = com.natsx.smartcafe.common.db.JdbcSqlDialect.consumeGuestJoinThrottle(dialect);
        String key = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "");

        jdbc.update(statement, key);
        assertEquals(1, jdbc.queryForObject(
            "SELECT attempts FROM guest_join_throttles WHERE bucket_hash=?",
            Integer.class, key));
        jdbc.update(statement, key);
        assertEquals(2, jdbc.queryForObject(
            "SELECT attempts FROM guest_join_throttles WHERE bucket_hash=?",
            Integer.class, key));

        // Simulate an expired fixed-window bucket. A new attempt must
        // reset to one rather than carrying a stale lockout indefinitely.
        jdbc.update("""
            UPDATE guest_join_throttles
            SET window_started_at=NOW(6)-INTERVAL 11 MINUTE
            WHERE bucket_hash=?
            """, key);
        jdbc.update(statement, key);
        assertEquals(1, jdbc.queryForObject(
            "SELECT attempts FROM guest_join_throttles WHERE bucket_hash=?",
            Integer.class, key));

        jdbc.update("""
            UPDATE guest_join_throttles
            SET updated_at=NOW(6)-INTERVAL 3 DAY WHERE bucket_hash=?
            """, key);
        jdbc.update(com.natsx.smartcafe.common.db.JdbcSqlDialect.purgeGuestJoinThrottle(dialect));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM guest_join_throttles WHERE bucket_hash=?",
            Integer.class, key));
    }

    @Test
    void mySqlGuestJoinUsesSharedLockOnVisitAliasWithoutLockingPublicTable() {
        String staff = newStaff();
        String table = newTable();
        String visit = newVisit(table, staff);

        String visitId = jdbc.queryForObject("""
            SELECT v.id
            FROM cafe_tables t JOIN table_visits v ON v.table_id=t.id
            WHERE t.public_alias=? AND v.status='ACTIVE'
            FOR SHARE OF v
            """, (rs, row) -> com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id").toString(),
            "mysql-" + table);

        assertEquals(visit, visitId);
    }

    @Test
    void mySqlVisitForeignKeyReadsUuidThroughDialectAdapter() {
        String staff = newStaff();
        String table = newTable();
        String visit = newVisit(table, staff);
        UUID fromDb = jdbc.queryForObject(
            "SELECT table_id FROM table_visits WHERE id=?",
            (rs, row) -> com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "table_id"),
            com.natsx.smartcafe.common.db.JdbcUuid.parameter(
                UUID.fromString(visit),
                com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc))
        );
        assertEquals(UUID.fromString(table), fromDb);
    }

    @Test
    void receiptRequiresPaidExactAmountAndEvidenceIsImmutable() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Fictional guest");
        String order = id();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,?,?,?)
            """, order, visit, guest, 50000L, 50000L, "test-v1");
        String intent = id();
        jdbc.update("""
            INSERT INTO payment_intents(
                id,order_id,method_selected,amount_due_idr,amount_paid_idr,aggregate_status)
            VALUES (?,?,?,?,?,?)
            """, intent, order, "CASH", 50000L, 0L, "AWAITING_CASH");
        String receipt = id();

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            VALUES (?,?,?,?,?)
            """, receipt, intent, order, 50000L, "{}"));

        jdbc.update("""
            UPDATE payment_intents SET amount_paid_idr=50000,aggregate_status='PAID'
            WHERE id=?
            """, intent);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            VALUES (?,?,?,?,?)
            """, receipt, intent, order, 49999L, "{}"));
        jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            VALUES (?,?,?,?,?)
            """, receipt, intent, order, 50000L, "{}");
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipts WHERE id=?", Integer.class, receipt));

        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE receipts SET total_idr=100 WHERE id=?", receipt));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("DELETE FROM receipts WHERE id=?", receipt));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE orders SET total_idr=100 WHERE id=?", order));
    }

    @Test
    void menuStockTriggerAndVariantGroupingWorkUnderMySqlConstraints() {
        String category = id();
        String item = id();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category, "Test category", "mysql-" + category);
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr)
            VALUES (?,?,?,?)
            """, item, category, "Synthetic item", 25000L);
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM item_availability WHERE menu_item_id=?",
            Integer.class, item));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE item_availability SET limited_quantity=-1 WHERE menu_item_id=?",
                item));

        String groupOne = id();
        String groupTwo = id();
        jdbc.update("""
            INSERT INTO menu_variant_groups(id,menu_item_id,name,required)
            VALUES (?,?,?,?)
            """, groupOne, item, "Size", true);
        jdbc.update("""
            INSERT INTO menu_variant_groups(id,menu_item_id,name,required)
            VALUES (?,?,?,?)
            """, groupTwo, item, "Milk", false);
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM menu_variant_groups WHERE menu_item_id=?",
            Integer.class, item));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO menu_variant_groups(id,menu_item_id,name,required)
                VALUES (?,?,?,?)
                """, id(), item, "size", true));
    }

    @Test
    void serializedGameCopiesCannotBeDoubleLoanedAndVisitRequestsAreUnique() {
        String staff = newStaff();
        String table1 = newTable();
        String table2 = newTable();
        String visit1 = newVisit(table1, staff);
        String visit2 = newVisit(table2, staff);
        String guest1 = id();
        String guest2 = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest1, visit1, "Synthetic guest A");
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest2, visit2, "Synthetic guest B");
        String game = id();
        String copy = id();
        jdbc.update("INSERT INTO physical_games(id,name) VALUES (?,?)",
            game, "Synthetic game " + game);
        jdbc.update("INSERT INTO physical_game_copies(id,game_id,copy_code) VALUES (?,?,?)",
            copy, game, "CP-" + copy);

        String waiting = id();
        jdbc.update("""
            INSERT INTO physical_game_loans(id,visit_id,guest_id,game_id)
            VALUES (?,?,?,?)
            """, waiting, visit1, guest1, game);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO physical_game_loans(id,visit_id,guest_id,game_id)
            VALUES (?,?,?,?)
            """, id(), visit1, guest1, game));
        jdbc.update("UPDATE physical_game_loans SET status='CANCELLED' WHERE id=?", waiting);

        String assigned = id();
        jdbc.update("""
            INSERT INTO physical_game_loans(
                id,visit_id,guest_id,game_id,copy_id,status,assigned_at)
            VALUES (?,?,?,?,?,'ASSIGNED',CURRENT_TIMESTAMP(6))
            """, assigned, visit1, guest1, game, copy);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO physical_game_loans(
                id,visit_id,guest_id,game_id,copy_id,status,assigned_at)
            VALUES (?,?,?,?,?,'ASSIGNED',CURRENT_TIMESTAMP(6))
            """, id(), visit2, guest2, game, copy));
        jdbc.update("""
            UPDATE physical_game_loans
            SET status='RETURNED',
                handed_over_at=CURRENT_TIMESTAMP(6),
                returned_at=CURRENT_TIMESTAMP(6)
            WHERE id=?
            """, assigned);
        jdbc.update("""
            INSERT INTO physical_game_loans(
                id,visit_id,guest_id,game_id,copy_id,status,assigned_at)
            VALUES (?,?,?,?,?,'ASSIGNED',CURRENT_TIMESTAMP(6))
            """, id(), visit2, guest2, game, copy);
    }

    @Test
    void mysqlUuidDialectParameterAndReadAdapterRoundTrip() {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO staff_users(id,login,password_hash)
            VALUES (?,?,?)
            """, com.natsx.smartcafe.common.db.JdbcUuid.parameter(
                userId, com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)),
            "uuid-" + userId, "synthetic-test-hash");
        UUID fromDb = jdbc.queryForObject("""
            SELECT id FROM staff_users WHERE id=?
            """, (rs, n) -> com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
            userId.toString());
        assertEquals(userId, fromDb);
    }

    @Test
    void atMostOneActiveCashShiftAndClosedShiftIsImmutable() {
        String staff = newStaff();
        String first = id();
        jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,?,?)
            """, first, staff, 20000L, "open-" + first);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,?,?)
            """, id(), staff, 30000L, "open-" + id()));

        jdbc.update("""
            UPDATE cash_drawer_shifts
            SET closed_at=CURRENT_TIMESTAMP(6),closed_by=?,close_key=?,
                counted_cash_idr=20000,recorded_cash_idr=0,
                expected_drawer_idr=20000,difference_idr=0,closing_reason='test close'
            WHERE id=?
            """, staff, "close-" + first, first);
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE cash_drawer_shifts SET closing_reason='changed' WHERE id=?", first));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("DELETE FROM cash_drawer_shifts WHERE id=?", first));
        String second=id();
        jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,?,?)
            """, second, staff, 30000L, "open-" + second);
        // Leave no active till for other isolated tests; the completed row
        // itself remains immutable, which is also the intended audit rule.
        jdbc.update("""
            UPDATE cash_drawer_shifts
            SET closed_at=CURRENT_TIMESTAMP(6),closed_by=?,close_key=?,
                counted_cash_idr=30000,recorded_cash_idr=0,
                expected_drawer_idr=30000,difference_idr=0,
                closing_reason='Second synthetic test shift closed'
            WHERE id=?
            """,staff,"close-"+second,second);
    }

    @Test
    void fakeDigitalAttemptsCannotAttachToCashOrCreateDuplicatePendingCharges() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic guest");
        String order = id();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,?,?,?)
            """, order, visit, guest, 35000L, 35000L, "test-v1");
        String intent = id();
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,?,?,?)
            """, intent, order, "CASH", 35000L, "AWAITING_CASH");

        String fakeRef = "FAKE_" + id().replace("-", "");
        String firstAttempt = id();
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO digital_payment_attempts(
                id,payment_intent_id,method,provider_key,provider_reference,
                idempotency_key,amount_idr,expires_at)
            VALUES (?,?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 10 MINUTE))
            """, firstAttempt, intent, "QRIS", "SANDBOX_FAKE", fakeRef,
            "sandbox_fake_key_00001", 35000L));

        jdbc.update("UPDATE payment_intents SET method_selected='QRIS',aggregate_status='PENDING' WHERE id=?",
            intent);
        jdbc.update("""
            INSERT INTO digital_payment_attempts(
                id,payment_intent_id,method,provider_key,provider_reference,
                idempotency_key,amount_idr,expires_at)
            VALUES (?,?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 10 MINUTE))
            """, firstAttempt, intent, "QRIS", "SANDBOX_FAKE", fakeRef,
            "sandbox_fake_key_00001", 35000L);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            INSERT INTO digital_payment_attempts(
                id,payment_intent_id,method,provider_key,provider_reference,
                idempotency_key,amount_idr,expires_at)
            VALUES (?,?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 10 MINUTE))
            """, id(), intent, "QRIS", "SANDBOX_FAKE",
            "FAKE_" + id().replace("-", ""), "sandbox_fake_key_00002", 35000L));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE digital_payment_attempts SET state='SUCCEEDED' WHERE id=?",
                firstAttempt));
        jdbc.update("UPDATE digital_payment_attempts SET state='PENDING' WHERE id=?",
            firstAttempt);
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE digital_payment_attempts SET amount_idr=5 WHERE id=?",
                firstAttempt));
    }

    @Test
    void mysqlThrottleAndCartIdempotencyPreserveExistingValues() {
        var vendor = com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        assertEquals(com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL, vendor);
        String throttleSql =
            com.natsx.smartcafe.common.db.JdbcSqlDialect.ensureStaffLoginThrottle(vendor);
        String loginHash = "a".repeat(64);
        jdbc.update(throttleSql, loginHash);
        jdbc.update("UPDATE staff_login_throttles SET failed_attempts=4 WHERE login_hash=?",
            loginHash);
        jdbc.update(throttleSql, loginHash);
        assertEquals(4, jdbc.queryForObject(
            "SELECT failed_attempts FROM staff_login_throttles WHERE login_hash=?",
            Integer.class, loginHash));

        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic cart owner");
        String cartSql =
            com.natsx.smartcafe.common.db.JdbcSqlDialect.ensureGuestCart(vendor);
        jdbc.update(cartSql, guest, visit);
        jdbc.update("UPDATE carts SET version=7 WHERE guest_id=?", guest);
        jdbc.update(cartSql, guest, visit);
        assertEquals(7L, jdbc.queryForObject(
            "SELECT version FROM carts WHERE guest_id=?", Long.class, guest));
    }

    @Test
    void financialAndPhysicalStateEnumerationsRejectIncorrectCase() {
        String staff = newStaff();
        String visit = newVisit(newTable(), staff);
        String guest = id();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic guest");
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE table_visits SET status='active' WHERE id=?", visit));
        String order = id();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,?,?,?)
            """,order,visit,guest,10000L,10000L,"test-case-sensitive");
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE orders SET status='accepted' WHERE id=?", order));
        String intent = id();
        jdbc.update("""
            INSERT INTO payment_intents(
              id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,?,?,?)
            """,intent,order,"CASH",10000L,"AWAITING_CASH");
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE payment_intents SET aggregate_status='paid' WHERE id=?",intent));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("UPDATE payment_intents SET method_selected='cash' WHERE id=?",intent));
    }

    @Test
    void coreForeignKeysCheckConstraintsAndStaffRolesAreEnforced() {
        assertEquals(5, jdbc.queryForObject(
            "SELECT COUNT(*) FROM staff_roles", Integer.class));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("""
                INSERT INTO payment_intents(
                    id,order_id,method_selected,amount_due_idr,aggregate_status)
                VALUES (?,?,?,?,?)
                """, id(), id(), "CASH", 10L, "AWAITING_CASH"));
        assertThrows(DataAccessException.class, () ->
            jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
                id(), "bad-" + id(), "bad-" + id(), -1));
    }
}
