package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.audit.AuditTrail;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.common.events.OutboxEvents;
import com.natsx.smartcafe.payments.CashTenderCalculator;
import com.natsx.smartcafe.payments.PaymentStatus;
import com.natsx.smartcafe.receipts.GuestReceiptService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashWorkflowService {
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;
    private final OutboxEvents outbox;
    private final GuestReceiptService receipts;

    public CashWorkflowService(JdbcTemplate jdbc, AuditTrail audit, OutboxEvents outbox,
                               GuestReceiptService receipts) {
        this.jdbc=jdbc;this.audit=audit;this.outbox=outbox;this.receipts=receipts;
    }

    public record OpsOrder(UUID id,String tableCode,String orderStatus,
                           String paymentStatus,long totalIdr) {}
    public record KitchenTicket(UUID id,UUID orderId,String tableCode,String status,
                                List<KitchenLine> lines) {}
    public record KitchenLine(String name,int quantity,String notes,String variantLabel) {}
    public record CashTenderResult(UUID paymentIntentId,UUID orderId,
                                   long receivedIdr,long changeGivenIdr,String paymentStatus,
                                   UUID receiptId) {}
    private record OrderRow(UUID id,String status,UUID visitId,long total) {}
    private record PaymentRow(UUID id,String status,String method,long due) {}
    private record TenderRow(long received,long change) {}
    private record TicketRow(UUID id,UUID orderId,String status) {}

    @Transactional(readOnly=true)
    public List<OpsOrder> queue() {
        return jdbc.query("""
            SELECT o.id,t.table_code,o.status,p.aggregate_status,o.total_idr FROM orders o
            JOIN table_visits v ON v.id=o.visit_id JOIN cafe_tables t ON t.id=v.table_id
            JOIN payment_intents p ON p.order_id=o.id
            ORDER BY o.created_at DESC LIMIT 100
            """,(rs,n)->new OpsOrder(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),rs.getString("table_code"),
                rs.getString("status"),rs.getString("aggregate_status"),rs.getLong("total_idr")));
    }

    @Transactional
    public void approve(UUID orderId,UUID staff,String requestId) {
        var order=lockOrder(orderId);
        var payment=lockPaymentByOrder(orderId);
        if (!"ACTIVE".equals(jdbc.queryForObject(
            "SELECT status FROM table_visits WHERE id=?",String.class,dbUuid(order.visitId())))) {
            throw conflict("Table visit has ended");
        }
        try {
            CashOrderPolicy.approve(OrderStatus.valueOf(order.status()),
                PaymentStatus.valueOf(payment.status()));
        } catch (IllegalStateException ex) {
            throw conflict("Cash order is not awaiting approval");
        }
        jdbc.update("UPDATE orders SET status='ACCEPTED',accepted_at=now(),version=version+1 WHERE id=?",dbUuid(orderId));
        jdbc.update("INSERT INTO kitchen_tickets(order_id,status) VALUES (?,'ACCEPTED')",dbUuid(orderId));
        audit.staff(staff,"CASH_ORDER_APPROVED","ORDER",orderId,requestId);
        outbox.order(orderId,"ORDER_ACCEPTED");
    }


    /**
     * Only submitted, unpaid, never-admitted cash orders may be rejected.
     * Returning reservations and changing order state are one locked transaction.
     */
    @Transactional
    public void reject(UUID orderId,UUID staff,String reason,String requestId) {
        var order=lockOrder(orderId);
        var payment=lockPaymentByOrder(orderId);
        if(!order.status().equals("SUBMITTED") || !payment.status().equals("AWAITING_CASH"))
            throw conflict("Only submitted unpaid cash orders can be rejected");
        Integer ticketCount=jdbc.queryForObject("""
            SELECT count(*) FROM kitchen_tickets WHERE order_id=?
            """,Integer.class,dbUuid(orderId));
        if(ticketCount==null || ticketCount!=0)throw conflict("Already admitted to kitchen");
        var allocations=jdbc.query("""
            SELECT menu_item_id,quantity,released_at FROM order_stock_allocations
            WHERE order_id=? ORDER BY menu_item_id FOR UPDATE
            """,(rs,n)->new StockAllocation(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "menu_item_id"),
                rs.getInt("quantity"),rs.getTimestamp("released_at")),dbUuid(orderId));
        Integer lines=jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id=?",
            Integer.class,dbUuid(orderId));
        if(lines==null || allocations.size()!=lines || allocations.isEmpty())
            throw conflict("Cannot safely release an order missing stock allocation evidence");
        for(var allocation:allocations) {
            if(allocation.releasedAt()!=null)throw conflict("Order allocations already released");
            jdbc.update("""
                UPDATE item_availability SET limited_quantity=limited_quantity+?,version=version+1
                WHERE menu_item_id=? AND limited_quantity IS NOT NULL
                """,allocation.quantity(),dbUuid(allocation.itemId()));
            int updated=jdbc.update("""
                UPDATE order_stock_allocations SET released_at=now()
                WHERE order_id=? AND menu_item_id=? AND released_at IS NULL
                """,dbUuid(orderId),dbUuid(allocation.itemId()));
            if(updated!=1)throw conflict("Concurrent stock release detected");
        }
        jdbc.update("UPDATE orders SET status='REJECTED',version=version+1 WHERE id=?",dbUuid(orderId));
        jdbc.update("""
            UPDATE payment_intents SET aggregate_status='UNPAID',version=version+1 WHERE id=?
            """,dbUuid(payment.id()));
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,resource_id,reason,correlation_id)
            VALUES ('STAFF',?,'CASH_ORDER_REJECTED','ORDER',?,?,?)
            """,dbUuid(staff),dbUuid(orderId),reason,requestId);
        outbox.order(orderId,"CASH_ORDER_REJECTED");
    }

    private record StockAllocation(UUID itemId,int quantity,java.sql.Timestamp releasedAt) {}

    @Transactional(readOnly=true)
    public List<KitchenTicket> kitchen() {
        var tickets=jdbc.query("""
            SELECT k.id,k.order_id,t.table_code,k.status FROM kitchen_tickets k
            JOIN orders o ON o.id=k.order_id
            JOIN table_visits v ON v.id=o.visit_id JOIN cafe_tables t ON t.id=v.table_id
            WHERE k.status IN ('ACCEPTED','PREPARING','READY')
            ORDER BY k.admitted_at ASC LIMIT 100
            """,(rs,n)->new TicketRowView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "order_id"),rs.getString("table_code"),rs.getString("status")));
        return tickets.stream().map(ticket->{
            var lines=jdbc.query(kitchenLinesSql(),
                (rs,n)->new KitchenLine(rs.getString("name_snapshot"),rs.getInt("quantity"),
                    rs.getString("notes"),rs.getString("variant_label")),
                dbUuid(ticket.orderId()));
            return new KitchenTicket(ticket.id(),ticket.orderId(),ticket.tableCode(),ticket.status(),lines);
        }).toList();
    }

    public record KitchenPage(int page,int pageSize,long total,boolean hasNext,
                              List<KitchenTicket> items) {}
    private static final int KITCHEN_PAGE_SIZE=25;
    private static final String KITCHEN_FROM = """
        FROM kitchen_tickets k
        JOIN orders o ON o.id=k.order_id
        JOIN table_visits v ON v.id=o.visit_id
        JOIN cafe_tables t ON t.id=v.table_id
        WHERE k.status IN ('ACCEPTED','PREPARING','READY')
        """;

    /**
     * PREPARING tickets must not be hidden by newer READY acknowledgments.
     * ACCEPTED work follows, then READY. Oldest work wins within each lane.
     * Every page and its count use one PostgreSQL repeatable-read snapshot.
     */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public KitchenPage kitchenPage(int page) {
        if(page<0||page>1000) throw new ApiFailureException(HttpStatus.BAD_REQUEST,
            "INVALID_INPUT","Invalid kitchen ticket page");
        Long total=jdbc.queryForObject("SELECT count(*) " + KITCHEN_FROM,Long.class);
        var rows=jdbc.query("""
            SELECT k.id,k.order_id,t.table_code,k.status
            """ + KITCHEN_FROM + """
            ORDER BY CASE k.status
                WHEN 'PREPARING' THEN 0 WHEN 'ACCEPTED' THEN 1 ELSE 2 END,
                k.admitted_at ASC,k.id ASC
            LIMIT ? OFFSET ?
            """,(rs,n)->new TicketRowView(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "order_id"),rs.getString("table_code"),
                rs.getString("status")),KITCHEN_PAGE_SIZE+1,page*KITCHEN_PAGE_SIZE);
        boolean hasNext=rows.size()>KITCHEN_PAGE_SIZE;
        var selected=rows.subList(0,Math.min(KITCHEN_PAGE_SIZE,rows.size()));
        var items=selected.stream().map(ticket->{
            var lines=jdbc.query(kitchenLinesSql(),
                (rs,n)->new KitchenLine(rs.getString("name_snapshot"),rs.getInt("quantity"),
                    rs.getString("notes"),rs.getString("variant_label")),
                dbUuid(ticket.orderId()));
            return new KitchenTicket(ticket.id(),ticket.orderId(),ticket.tableCode(),
                ticket.status(),lines);
        }).toList();
        return new KitchenPage(page,KITCHEN_PAGE_SIZE,total==null?0:total,hasNext,items);
    }

    private record TicketRowView(UUID id,UUID orderId,String tableCode,String status) {}

    @Transactional
    public void kitchenStart(UUID ticketId,UUID staff,String requestId) {
        var ticket=findTicket(ticketId);
        if(ticket==null) throw missing();
        var order=lockOrder(ticket.orderId());
        var payment=lockPaymentByOrder(order.id());
        // Ticket status may be stale if another kitchen member won the race.
        ticket=lockTicket(ticketId);
        if (!ticket.status().equals("ACCEPTED")) throw conflict("Ticket already started");
        try {
            CashOrderPolicy.startPreparation(OrderStatus.valueOf(order.status()),
                PaymentStatus.valueOf(payment.status()));
        } catch(IllegalStateException ex) { throw conflict("Order not admitted to kitchen"); }
        jdbc.update("UPDATE orders SET status='PREPARING',version=version+1 WHERE id=?",dbUuid(order.id()));
        jdbc.update("UPDATE kitchen_tickets SET status='PREPARING',preparing_at=now(),version=version+1 WHERE id=?",dbUuid(ticketId));
        audit.staff(staff,"KITCHEN_STARTED","KITCHEN_TICKET",ticketId,requestId);
        outbox.order(order.id(),"ORDER_PREPARING");
    }

    @Transactional
    public void kitchenReady(UUID ticketId,UUID staff,String requestId) {
        var ticket=findTicket(ticketId);
        var order=lockOrder(ticket.orderId());
        ticket=lockTicket(ticketId);
        if (!ticket.status().equals("PREPARING")) throw conflict("Ticket is not preparing");
        try {
            CashOrderPolicy.markReady(OrderStatus.valueOf(order.status()));
        } catch(IllegalStateException ex) { throw conflict("Order cannot become ready"); }
        jdbc.update("UPDATE orders SET status='READY',version=version+1 WHERE id=?",dbUuid(order.id()));
        jdbc.update("UPDATE kitchen_tickets SET status='READY',ready_at=now(),version=version+1 WHERE id=?",dbUuid(ticketId));
        audit.staff(staff,"KITCHEN_READY","KITCHEN_TICKET",ticketId,requestId);
        outbox.order(order.id(),"ORDER_READY");
    }

    @Transactional
    public void served(UUID orderId,UUID staff,String requestId) {
        var order=lockOrder(orderId);
        requireWaiterOwnershipOrSupervisor(orderId, staff);
        try {
            CashOrderPolicy.markServed(OrderStatus.valueOf(order.status()));
        } catch(IllegalStateException ex) { throw conflict("Order is not ready to serve"); }
        jdbc.update("UPDATE orders SET status='SERVED',version=version+1 WHERE id=?",dbUuid(orderId));
        audit.staff(staff,"ORDER_SERVED","ORDER",orderId,requestId);
        outbox.order(orderId,"ORDER_SERVED");
    }

    @Transactional
    public CashTenderResult capture(UUID intentId,long receivedIdr,String key,UUID staff,String requestId) {
        if(key==null || key.length()<8 || key.length()>128 || !key.matches("[a-zA-Z0-9_-]+")) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid idempotency key");
        }
        UUID orderId=jdbc.query("SELECT order_id FROM payment_intents WHERE id=?",
            rs->rs.next()?com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"order_id"):null,
            dbUuid(intentId));
        if(orderId==null) throw missing();
        var order=lockOrder(orderId);
        requireWaiterOwnershipOrSupervisor(orderId, staff);
        var payment=lockPaymentByOrder(orderId);
        if(!payment.id().equals(intentId)) throw missing();
        if(!"CASH".equals(payment.method())) {
            throw conflict("Only cash payment intents may accept physical tender");
        }

        var replay=jdbc.query("""
            SELECT amount_received_idr,change_given_idr FROM cash_tenders
            WHERE payment_intent_id=? AND idempotency_key=?
            """,(rs,n)->new TenderRow(rs.getLong(1),rs.getLong(2)),dbUuid(intentId),key);
        if(!replay.isEmpty()) {
            var old=replay.getFirst();
            if(old.received()!=receivedIdr) throw conflict("Idempotency key reused with different amount");
            UUID receiptId=jdbc.query("""
                SELECT id FROM receipts WHERE payment_intent_id=?
                """,rs->rs.next()?com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"):null,
                dbUuid(intentId));
            if(receiptId==null) throw new IllegalStateException("Paid cash tender has no receipt");
            return new CashTenderResult(intentId,orderId,old.received(),old.change(),"PAID",receiptId);
        }

        // Cash approval and serving are NEVER equivalent to collecting money.
        if(!"SERVED".equals(order.status())) {
            throw conflict("Cash capture requires actual service handover");
        }
        com.natsx.smartcafe.payments.CashTenderQuote quote;
        try {
            quote=CashTenderCalculator.quote(payment.due(),receivedIdr,
                PaymentStatus.valueOf(payment.status()));
        } catch(IllegalStateException ex) {
            throw conflict("Payment is not awaiting cash");
        } catch(IllegalArgumentException ex) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Cash amount insufficient");
        }

        var vendor=com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        if (vendor==com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL &&
            !org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException("MySQL cash tender requires a database transaction");
        }

        // PostgreSQL keeps its transaction advisory fence. MySQL instead
        // locks the EXISTING active cash shift row through commit, which
        // serializes this tender against shift close/handoff. Unlike a
        // MySQL connection-lifetime GET_LOCK, no pool-level lock survives.
        if(vendor==com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.POSTGRESQL) {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(48725021)", (rs,n)->0);
        }
        String activeShiftSql = "SELECT id FROM cash_drawer_shifts WHERE closed_at IS NULL LIMIT 1"
            + (vendor==com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
                ? " FOR UPDATE" : "");
        UUID shiftId=jdbc.query(activeShiftSql,rs->rs.next()?
            com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"):null);

        if(shiftId==null) {
            // A MySQL cash tender must never be recorded without a stable
            // till row to lock. PostgreSQL legacy first-shift demo behavior
            // stays unchanged until the separate shift port is complete.
            if(vendor==com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL) {
                throw conflict("Open a cash drawer shift before recording cash");
            }
            Integer prior=jdbc.queryForObject("SELECT count(*) FROM cash_drawer_shifts",
                Integer.class);
            if(prior!=null && prior>0) {
                throw conflict("Open a cash drawer shift before capturing another cash payment");
            }
        }

        UUID tenderId;
        if(vendor==com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.POSTGRESQL) {
            tenderId=jdbc.queryForObject("""
                INSERT INTO cash_tenders(payment_intent_id,staff_user_id,amount_received_idr,
                    change_given_idr,idempotency_key,cash_shift_id,recorded_at)
                VALUES (?,?,?,?,?,?,clock_timestamp()) RETURNING id
                """,UUID.class,dbUuid(intentId),dbUuid(staff),quote.amountReceivedIdr(),
                quote.changeGivenIdr(),key,dbUuid(shiftId));
        } else {
            tenderId=UUID.randomUUID();
            int inserted=jdbc.update("""
                INSERT INTO cash_tenders(id,payment_intent_id,staff_user_id,amount_received_idr,
                    change_given_idr,idempotency_key,cash_shift_id,recorded_at)
                VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """,dbUuid(tenderId),dbUuid(intentId),dbUuid(staff),quote.amountReceivedIdr(),
                quote.changeGivenIdr(),key,dbUuid(shiftId));
            if(inserted!=1)throw new IllegalStateException("Cash tender was not recorded");
        }

        if(shiftId!=null) {
            Boolean waiterOnly=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_role_assignments
                    WHERE staff_user_id=? AND role_key='WAITER')
                  AND NOT EXISTS(SELECT 1 FROM staff_role_assignments
                    WHERE staff_user_id=? AND role_key IN ('CASHIER','MANAGER','ADMIN'))
                """,Boolean.class,dbUuid(staff),dbUuid(staff));
            if(Boolean.TRUE.equals(waiterOnly)) {
                jdbc.update("""
                    INSERT INTO cash_tender_handoffs(tender_id,cash_shift_id,waiter_staff_id)
                    VALUES (?,?,?)
                    """,dbUuid(tenderId),dbUuid(shiftId),dbUuid(staff));
            }
        }

        int changed=jdbc.update("""
            UPDATE payment_intents SET amount_paid_idr=?,aggregate_status='PAID',version=version+1
            WHERE id=? AND aggregate_status='AWAITING_CASH'
            """,payment.due(),dbUuid(intentId));
        if(changed!=1)throw conflict("Cash payment state changed during capture");
        UUID receiptId=receipts.issueCash(intentId,orderId,payment.due());
        audit.staff(staff,"CASH_COLLECTED","PAYMENT_INTENT",intentId,requestId);
        outbox.order(orderId,"CASH_PAYMENT_PAID");
        return new CashTenderResult(intentId,orderId,receivedIdr,
            quote.changeGivenIdr(),"PAID",receiptId);
    }

    private void requireWaiterOwnershipOrSupervisor(UUID orderId, UUID staffId) {
        Boolean supervisor = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM staff_role_assignments
                          WHERE staff_user_id=? AND role_key IN ('CASHIER','MANAGER','ADMIN'))
            """, Boolean.class, dbUuid(staffId));
        if (Boolean.TRUE.equals(supervisor)) return;
        Boolean assigned = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM orders o JOIN table_visits v ON v.id=o.visit_id
                          WHERE o.id=? AND v.opened_by=?)
            """, Boolean.class, dbUuid(orderId), dbUuid(staffId));
        if (!Boolean.TRUE.equals(assigned)) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Waiter is not responsible for this visit");
        }
    }

    private TicketRow findTicket(UUID id) {
        return jdbc.query("SELECT id,order_id,status FROM kitchen_tickets WHERE id=?",
            rs->rs.next()?new TicketRow(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "order_id"),rs.getString("status")):null,dbUuid(id));
    }
    private TicketRow lockTicket(UUID id) {
        var found=jdbc.query("SELECT id,order_id,status FROM kitchen_tickets WHERE id=? FOR UPDATE",
            rs->rs.next()?new TicketRow(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "order_id"),rs.getString("status")):null,dbUuid(id));
        if(found==null)throw missing();
        return found;
    }
    private OrderRow lockOrder(UUID id) {
        var result=jdbc.query("SELECT id,status,visit_id,total_idr FROM orders WHERE id=? FOR UPDATE",
            rs->rs.next()?new OrderRow(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),rs.getString("status"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "visit_id"),rs.getLong("total_idr")):null,dbUuid(id));
        if(result==null)throw missing();
        return result;
    }
    private PaymentRow lockPaymentByOrder(UUID orderId) {
        var result=jdbc.query("""
            SELECT id,aggregate_status,method_selected,amount_due_idr
            FROM payment_intents WHERE order_id=? FOR UPDATE
            """,rs->rs.next()?new PaymentRow(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),rs.getString("aggregate_status"),rs.getString("method_selected"),
                rs.getLong("amount_due_idr")):null,dbUuid(orderId));
        if(result==null)throw missing();
        return result;
    }
    private Object dbUuid(UUID id) {
        return com.natsx.smartcafe.common.db.JdbcUuid.parameter(
            id, com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc));
    }

    /** PostgreSQL JSONB field access differs from MySQL JSON path syntax. */
    private String kitchenLinesSql() {
        var vendor = com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        String variantLabel = switch (vendor) {
            case POSTGRESQL -> "variant_snapshot->>'label'";
            case MYSQL -> "JSON_UNQUOTE(JSON_EXTRACT(variant_snapshot,'$.label'))";
        };
        return "SELECT name_snapshot,quantity,notes," + variantLabel
            + " AS variant_label FROM order_lines WHERE order_id=? ORDER BY name_snapshot";
    }

    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",message);
    }
    private ApiFailureException missing() {
        return new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Order not available");
    }
}
