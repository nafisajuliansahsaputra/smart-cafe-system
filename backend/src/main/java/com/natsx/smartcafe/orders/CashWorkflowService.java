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
    private record PaymentRow(UUID id,String status,long due) {}
    private record TenderRow(long received,long change) {}
    private record TicketRow(UUID id,UUID orderId,String status) {}

    @Transactional(readOnly=true)
    public List<OpsOrder> queue() {
        return jdbc.query("""
            SELECT o.id,t.table_code,o.status,p.aggregate_status,o.total_idr FROM orders o
            JOIN table_visits v ON v.id=o.visit_id JOIN cafe_tables t ON t.id=v.table_id
            JOIN payment_intents p ON p.order_id=o.id
            ORDER BY o.created_at DESC LIMIT 100
            """,(rs,n)->new OpsOrder(rs.getObject("id",UUID.class),rs.getString("table_code"),
                rs.getString("status"),rs.getString("aggregate_status"),rs.getLong("total_idr")));
    }

    @Transactional
    public void approve(UUID orderId,UUID staff,String requestId) {
        var order=lockOrder(orderId);
        var payment=lockPaymentByOrder(orderId);
        if (!"ACTIVE".equals(jdbc.queryForObject(
            "SELECT status FROM table_visits WHERE id=?",String.class,order.visitId()))) {
            throw conflict("Table visit has ended");
        }
        try {
            CashOrderPolicy.approve(OrderStatus.valueOf(order.status()),
                PaymentStatus.valueOf(payment.status()));
        } catch (IllegalStateException ex) {
            throw conflict("Cash order is not awaiting approval");
        }
        jdbc.update("UPDATE orders SET status='ACCEPTED',accepted_at=now(),version=version+1 WHERE id=?",orderId);
        jdbc.update("INSERT INTO kitchen_tickets(order_id,status) VALUES (?,'ACCEPTED')",orderId);
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
            """,Integer.class,orderId);
        if(ticketCount==null || ticketCount!=0)throw conflict("Already admitted to kitchen");
        var allocations=jdbc.query("""
            SELECT menu_item_id,quantity,released_at FROM order_stock_allocations
            WHERE order_id=? ORDER BY menu_item_id FOR UPDATE
            """,(rs,n)->new StockAllocation(rs.getObject("menu_item_id",UUID.class),
                rs.getInt("quantity"),rs.getTimestamp("released_at")),orderId);
        Integer lines=jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id=?",
            Integer.class,orderId);
        if(lines==null || allocations.size()!=lines || allocations.isEmpty())
            throw conflict("Cannot safely release an order missing stock allocation evidence");
        for(var allocation:allocations) {
            if(allocation.releasedAt()!=null)throw conflict("Order allocations already released");
            jdbc.update("""
                UPDATE item_availability SET limited_quantity=limited_quantity+?,version=version+1
                WHERE menu_item_id=? AND limited_quantity IS NOT NULL
                """,allocation.quantity(),allocation.itemId());
            int updated=jdbc.update("""
                UPDATE order_stock_allocations SET released_at=now()
                WHERE order_id=? AND menu_item_id=? AND released_at IS NULL
                """,orderId,allocation.itemId());
            if(updated!=1)throw conflict("Concurrent stock release detected");
        }
        jdbc.update("UPDATE orders SET status='REJECTED',version=version+1 WHERE id=?",orderId);
        jdbc.update("""
            UPDATE payment_intents SET aggregate_status='UNPAID',version=version+1 WHERE id=?
            """,payment.id());
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,resource_id,reason,correlation_id)
            VALUES ('STAFF',?,'CASH_ORDER_REJECTED','ORDER',?,?,?)
            """,staff,orderId,reason,requestId);
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
            """,(rs,n)->new TicketRowView(rs.getObject("id",UUID.class),
                rs.getObject("order_id",UUID.class),rs.getString("table_code"),rs.getString("status")));
        return tickets.stream().map(ticket->{
            var lines=jdbc.query("""
                SELECT name_snapshot,quantity,notes,variant_snapshot->>'label' AS variant_label
                FROM order_lines WHERE order_id=? ORDER BY name_snapshot
                """,(rs,n)->new KitchenLine(rs.getString("name_snapshot"),rs.getInt("quantity"),
                    rs.getString("notes"),rs.getString("variant_label")),ticket.orderId());
            return new KitchenTicket(ticket.id(),ticket.orderId(),ticket.tableCode(),ticket.status(),lines);
        }).toList();
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
        jdbc.update("UPDATE orders SET status='PREPARING',version=version+1 WHERE id=?",order.id());
        jdbc.update("UPDATE kitchen_tickets SET status='PREPARING',preparing_at=now(),version=version+1 WHERE id=?",ticketId);
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
        jdbc.update("UPDATE orders SET status='READY',version=version+1 WHERE id=?",order.id());
        jdbc.update("UPDATE kitchen_tickets SET status='READY',ready_at=now(),version=version+1 WHERE id=?",ticketId);
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
        jdbc.update("UPDATE orders SET status='SERVED',version=version+1 WHERE id=?",orderId);
        audit.staff(staff,"ORDER_SERVED","ORDER",orderId,requestId);
        outbox.order(orderId,"ORDER_SERVED");
    }

    @Transactional
    public CashTenderResult capture(UUID intentId,long receivedIdr,String key,UUID staff,String requestId) {
        if(key==null || key.length()<8 || key.length()>128 || !key.matches("[a-zA-Z0-9_-]+")) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid idempotency key");
        }
        UUID orderId=jdbc.query("SELECT order_id FROM payment_intents WHERE id=?",
            rs->rs.next()?rs.getObject(1,UUID.class):null,intentId);
        if(orderId==null) throw missing();
        var order=lockOrder(orderId);
        requireWaiterOwnershipOrSupervisor(orderId, staff);
        var payment=lockPaymentByOrder(orderId);
        if(!payment.id().equals(intentId)) throw missing();
        var replay=jdbc.query("""
            SELECT amount_received_idr,change_given_idr FROM cash_tenders
            WHERE payment_intent_id=? AND idempotency_key=?
            """,(rs,n)->new TenderRow(rs.getLong(1),rs.getLong(2)),intentId,key);
        if(!replay.isEmpty()) {
            var old=replay.getFirst();
            if(old.received()!=receivedIdr) throw conflict("Idempotency key reused with different amount");
            UUID receiptId=jdbc.queryForObject(
                "SELECT id FROM receipts WHERE payment_intent_id=?",UUID.class,intentId);
            return new CashTenderResult(intentId,orderId,old.received(),old.change(),"PAID",receiptId);
        }
        // Cash at handover is the conservative demo-only policy until the owner confirms cash-at-cashier.
        if(!order.status().equals("SERVED")) throw conflict("Cash capture requires actual service handover");
        com.natsx.smartcafe.payments.CashTenderQuote quote;
        try {
            quote=CashTenderCalculator.quote(payment.due(),receivedIdr,
                PaymentStatus.valueOf(payment.status()));
        } catch(IllegalStateException ex) {
            throw conflict("Payment is not awaiting cash");
        } catch(IllegalArgumentException ex) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Cash amount insufficient");
        }
        // The advisory fence is also held by close-shift and cashier handoff.
        // New tender commits cannot slip into a shift after its closing snapshot.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(48725021)", (rs,n)->0);
        UUID shiftId = jdbc.query("""
            SELECT id FROM cash_drawer_shifts WHERE closed_at IS NULL LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1,UUID.class) : null);
        if (shiftId == null) {
            Integer prior = jdbc.queryForObject("SELECT count(*) FROM cash_drawer_shifts",
                Integer.class);
            if (prior != null && prior > 0) {
                throw conflict("Open a cash drawer shift before capturing another cash payment");
            }
        }
        UUID tenderId = jdbc.queryForObject("""
            INSERT INTO cash_tenders(payment_intent_id,staff_user_id,amount_received_idr,
                change_given_idr,idempotency_key,cash_shift_id,recorded_at)
            VALUES (?,?,?,?,?,?,clock_timestamp()) RETURNING id
            """, UUID.class, intentId,staff,quote.amountReceivedIdr(),
            quote.changeGivenIdr(),key,shiftId);
        if (shiftId != null) {
            Boolean waiterOnly = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_role_assignments
                    WHERE staff_user_id=? AND role_key='WAITER')
                  AND NOT EXISTS(SELECT 1 FROM staff_role_assignments
                    WHERE staff_user_id=? AND role_key IN ('CASHIER','MANAGER','ADMIN'))
                """,Boolean.class,staff,staff);
            if (Boolean.TRUE.equals(waiterOnly)) {
                jdbc.update("""
                    INSERT INTO cash_tender_handoffs(tender_id,cash_shift_id,waiter_staff_id)
                    VALUES (?,?,?)
                    """,tenderId,shiftId,staff);
            }
        }
        jdbc.update("""
            UPDATE payment_intents SET amount_paid_idr=?,aggregate_status='PAID',version=version+1
            WHERE id=?
            """,payment.due(),intentId);
        UUID receiptId=receipts.issueCash(intentId,orderId,payment.due());
        audit.staff(staff,"CASH_COLLECTED","PAYMENT_INTENT",intentId,requestId);
        outbox.order(orderId,"CASH_PAYMENT_PAID");
        return new CashTenderResult(intentId,orderId,receivedIdr,quote.changeGivenIdr(),"PAID",receiptId);
    }

    private void requireWaiterOwnershipOrSupervisor(UUID orderId, UUID staffId) {
        Boolean supervisor = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM staff_role_assignments
                          WHERE staff_user_id=? AND role_key IN ('CASHIER','MANAGER','ADMIN'))
            """, Boolean.class, staffId);
        if (Boolean.TRUE.equals(supervisor)) return;
        Boolean assigned = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM orders o JOIN table_visits v ON v.id=o.visit_id
                          WHERE o.id=? AND v.opened_by=?)
            """, Boolean.class, orderId, staffId);
        if (!Boolean.TRUE.equals(assigned)) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Waiter is not responsible for this visit");
        }
    }

    private TicketRow findTicket(UUID id) {
        return jdbc.query("SELECT id,order_id,status FROM kitchen_tickets WHERE id=?",
            rs->rs.next()?new TicketRow(rs.getObject("id",UUID.class),
                rs.getObject("order_id",UUID.class),rs.getString("status")):null,id);
    }
    private TicketRow lockTicket(UUID id) {
        var found=jdbc.query("SELECT id,order_id,status FROM kitchen_tickets WHERE id=? FOR UPDATE",
            rs->rs.next()?new TicketRow(rs.getObject("id",UUID.class),
                rs.getObject("order_id",UUID.class),rs.getString("status")):null,id);
        if(found==null)throw missing();
        return found;
    }
    private OrderRow lockOrder(UUID id) {
        var result=jdbc.query("SELECT id,status,visit_id,total_idr FROM orders WHERE id=? FOR UPDATE",
            rs->rs.next()?new OrderRow(rs.getObject("id",UUID.class),rs.getString("status"),
                rs.getObject("visit_id",UUID.class),rs.getLong("total_idr")):null,id);
        if(result==null)throw missing();
        return result;
    }
    private PaymentRow lockPaymentByOrder(UUID orderId) {
        var result=jdbc.query("""
            SELECT id,aggregate_status,amount_due_idr FROM payment_intents WHERE order_id=? FOR UPDATE
            """,rs->rs.next()?new PaymentRow(rs.getObject("id",UUID.class),rs.getString("aggregate_status"),
                rs.getLong("amount_due_idr")):null,orderId);
        if(result==null)throw missing();
        return result;
    }
    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",message);
    }
    private ApiFailureException missing() {
        return new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Order not available");
    }
}
