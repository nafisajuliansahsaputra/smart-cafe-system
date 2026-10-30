package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.identity.SecureToken;
import com.natsx.smartcafe.common.events.OutboxEvents;
import com.natsx.smartcafe.catalog.VariantPricingService;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative checkout, money snapshots and idempotency in one PostgreSQL transaction. */
@Service
public class CashCheckoutService {
    private final JdbcTemplate jdbc;
    private final CartService carts;
    private final OutboxEvents outbox;
    private final VariantPricingService variants;
    private final boolean sandboxDigitalCheckoutEnabled;
    private final String paymentEnvironment;
    public CashCheckoutService(JdbcTemplate jdbc,CartService carts,OutboxEvents outbox,
                               VariantPricingService variants,
                               @org.springframework.beans.factory.annotation.Value(
                                   "${scs.payments.sandbox-digital-checkout-enabled:false}")
                                   boolean sandboxDigitalCheckoutEnabled,
                               @org.springframework.beans.factory.annotation.Value(
                                   "${scs.payments.environment:disabled}")
                                   String paymentEnvironment) {
        this.jdbc=jdbc;
        this.carts=carts;
        this.outbox=outbox;
        this.variants=variants;
        this.sandboxDigitalCheckoutEnabled=sandboxDigitalCheckoutEnabled;
        this.paymentEnvironment=paymentEnvironment;
    }

    public record Line(UUID menuItemId,int quantity,UUID variantOptionId,List<UUID> variantOptionIds) {
        public Line(UUID menuItemId,int quantity) { this(menuItemId,quantity,null,null); }
        public Line(UUID menuItemId,int quantity,UUID variantOptionId) {
            this(menuItemId,quantity,variantOptionId,null);
        }
        public List<UUID> selectedOptionIds() {
            if(variantOptionId!=null && variantOptionIds!=null)
                throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                    "Choose variantOptionId or variantOptionIds, not both");
            return variantOptionId!=null?List.of(variantOptionId)
                :variantOptionIds==null?List.of():variantOptionIds;
        }
    }
    public record Checkout(UUID id, String status, String paymentStatus, long totalIdr) {}
    private record MenuRow(UUID id, String name, long price) {}

    // Legacy CASH checkout remains identical when paymentMethod is omitted.
    @Transactional
    public Checkout checkout(GuestPrincipal guest,List<Line> lines,String key) {
        return checkout(guest,lines,key,null);
    }

    @Transactional
    public Checkout checkout(GuestPrincipal guest,List<Line> lines,String key,
                             String requestedPaymentMethod) {
        String selected=validatePaymentMethod(requestedPaymentMethod);
        return checkoutResolved(guest,lines,key,null,selected);
    }

    @Transactional
    public Checkout checkoutFromCart(GuestPrincipal guest,long expectedVersion,String key) {
        return checkoutFromCart(guest,expectedVersion,key,null);
    }

    @Transactional
    public Checkout checkoutFromCart(GuestPrincipal guest,long expectedVersion,String key,
                                    String requestedPaymentMethod) {
        String selected=validatePaymentMethod(requestedPaymentMethod);
        validateKey(key);
        if(expectedVersion<0)throw failure(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
            "Invalid cart version");
        // Preserve existing CASH key semantics, but bind QRIS/VA to the
        // selected method so one key cannot silently change payment type.
        String hash=SecureToken.sha256("CART|"+expectedVersion+
            ("CASH".equals(selected)?"":"|SANDBOX|"+selected));
        // Lock first: a completed checkout must remain replayable after cart consumption.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            Object.class,guest.guestId()+":"+key);
        var prior=jdbc.query("""
            SELECT i.request_hash,o.id,o.status,p.aggregate_status,o.total_idr
            FROM order_idempotency i JOIN orders o ON o.id=i.order_id
            JOIN payment_intents p ON p.order_id=o.id
            WHERE i.actor_id=? AND i.scope='CASH_CHECKOUT' AND i.idempotency_key=?
            """,(rs,n)->new Existing(rs.getObject("id",UUID.class),
                rs.getString("request_hash"),rs.getString("status"),rs.getLong("total_idr"),
                rs.getString("aggregate_status")),guest.guestId(),key);
        if(!prior.isEmpty()) {
            var found=prior.getFirst();
            if(!hash.equals(found.hash()))throw failure(HttpStatus.CONFLICT,"CONFLICT",
                "Idempotency key belongs to a different checkout");
            return new Checkout(found.id(),found.status(),found.payment(),found.total());
        }
        var lines=carts.lockForCheckout(guest,expectedVersion);
        var result=checkoutResolved(guest,lines,key,hash,selected);
        carts.consume(guest,expectedVersion);
        return result;
    }

    private String validatePaymentMethod(String requested) {
        String method=requested==null?"CASH":requested;
        if(!"CASH".equals(method)&&!"QRIS".equals(method)&&!"VA".equals(method)) {
            throw failure(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Payment method must be CASH, QRIS or VA");
        }
        if(!"CASH".equals(method)&&
           (!sandboxDigitalCheckoutEnabled||!"sandbox".equals(paymentEnvironment))) {
            // Two independent controls protect default deployments: merely
            // setting one value must not enable digital ordering.
            throw failure(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Digital checkout is disabled outside explicit sandbox mode");
        }
        return method;
    }

    private void validateKey(String key) {
        if(key==null || key.length()<8 || key.length()>128 || !key.matches("[a-zA-Z0-9_-]+"))
            throw failure(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid idempotency key");
    }

    private Checkout checkoutResolved(GuestPrincipal guest,List<Line> lines,String key,
                                      String overrideHash,String selectedMethod) {
        validateKey(key);
        if (lines == null || lines.isEmpty() || lines.size()>30) {
            throw failure(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Cart must contain 1 to 30 items");
        }
        var unique = new HashSet<UUID>();
        for (var line : lines) {
            if (line == null || line.menuItemId()==null || line.quantity()<1 || line.quantity()>20
                || !unique.add(line.menuItemId()) || line.selectedOptionIds().size()>8
                || line.selectedOptionIds().stream().anyMatch(x->x==null)
                || new HashSet<>(line.selectedOptionIds()).size()!=line.selectedOptionIds().size()) {
                throw failure(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid or duplicate cart line");
            }
        }
        var canonical = lines.stream().sorted(Comparator.comparing(x->x.menuItemId().toString()))
            .map(x->x.menuItemId()+":"+x.quantity()+
                (x.selectedOptionIds().isEmpty()?"":":"+
                    String.join(":",x.selectedOptionIds().stream()
                        .sorted(Comparator.comparing(UUID::toString))
                        .map(UUID::toString).toList()))).toList();
        String hash = overrideHash!=null?overrideHash:
            SecureToken.sha256(("CASH".equals(selectedMethod)?"CASH|":
                "SANDBOX|"+selectedMethod+"|")+String.join("|",canonical));
        // Serializes concurrent submissions for the same guest/key BEFORE reading idempotency.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
            guest.guestId()+":"+key);
        var existing = jdbc.query("""
            SELECT i.order_id,i.request_hash,o.status,o.total_idr,p.aggregate_status
            FROM order_idempotency i JOIN orders o ON o.id=i.order_id
            JOIN payment_intents p ON p.order_id=o.id
            WHERE i.actor_id=? AND i.scope='CASH_CHECKOUT' AND i.idempotency_key=?
            """, (rs,n)->new Existing(rs.getObject("order_id",UUID.class),rs.getString("request_hash"),
                rs.getString("status"),rs.getLong("total_idr"),rs.getString("aggregate_status")),
            guest.guestId(),key);
        if (!existing.isEmpty()) {
            var found=existing.getFirst();
            if (!found.hash().equals(hash)) throw failure(HttpStatus.CONFLICT,"CONFLICT",
                "Idempotency key already used with different cart");
            return new Checkout(found.id(),found.status(),found.payment(),found.total());
        }

        String visitState=jdbc.query("""
            SELECT status FROM table_visits WHERE id=? FOR SHARE
            """,rs->rs.next()?rs.getString(1):null,guest.visitId());
        if (!"ACTIVE".equals(visitState)) throw failure(HttpStatus.CONFLICT,"SESSION_CLOSED",
            "Table visit has ended");

        long subtotal=0;
        var items=new java.util.ArrayList<PricedLine>();
        for(var line:lines.stream().sorted(Comparator.comparing(x->x.menuItemId().toString())).toList()) {
            var found=jdbc.query("""
                SELECT i.id,i.name,i.base_price_idr FROM menu_items i
                JOIN menu_categories c ON c.id=i.category_id
                WHERE i.id=? AND i.availability='AVAILABLE' AND c.active=true
                FOR SHARE OF i
                """,(rs,n)->new MenuRow(rs.getObject("id",UUID.class),rs.getString("name"),
                    rs.getLong("base_price_idr")),line.menuItemId());
            if(found.isEmpty()) throw failure(HttpStatus.CONFLICT,"OUT_OF_STOCK",
                "Item is unavailable");
            // An UPDATE is the atomic last-unit guard. Rows also exist for unlimited items.
            int reserved=jdbc.update("""
                UPDATE item_availability
                SET limited_quantity=CASE WHEN limited_quantity IS NULL THEN NULL
                                          ELSE limited_quantity-? END,
                    version=version+1
                WHERE menu_item_id=? AND available=true
                  AND (limited_quantity IS NULL OR limited_quantity>=?)
                """,line.quantity(),line.menuItemId(),line.quantity());
            if(reserved!=1)throw failure(HttpStatus.CONFLICT,"OUT_OF_STOCK",
                "Insufficient menu item stock");
            var item=found.getFirst();
            var selection=variants.resolveSelections(item.id(),line.selectedOptionIds());
            long lineTotal;
            long unitPrice;
            try {
                unitPrice=Math.addExact(item.price(),selection.totalDeltaIdr());
                if(unitPrice<0)throw new ArithmeticException("Negative adjusted unit price");
                lineTotal=IdrMoney.lineTotal(unitPrice,line.quantity());
                subtotal=Math.addExact(subtotal,lineTotal);
            } catch (ArithmeticException e) {
                throw failure(HttpStatus.CONFLICT,"CONFLICT","Selected menu price is invalid");
            }
            items.add(new PricedLine(item,line.quantity(),lineTotal,unitPrice,selection));
        }
        if(subtotal<=0) throw failure(HttpStatus.CONFLICT,"CONFLICT",
            "Checkout requires a positive total");

        UUID orderId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,'SUBMITTED',?,?,'M2-DEMO-UNTAXED')
            """,orderId,guest.visitId(),guest.guestId(),subtotal,subtotal);
        for(var item:items) {
            jdbc.update("""
                INSERT INTO order_lines(order_id,menu_item_id,name_snapshot,variant_snapshot,
                                        unit_price_idr,quantity,line_total_idr)
                VALUES (?,?,?,CASE WHEN CAST(? AS text)='' THEN '{}'::jsonb
                    ELSE jsonb_build_object(
                      'label',CAST(? AS text),
                      'options',(
                        SELECT coalesce(jsonb_agg(jsonb_build_object(
                            'id',o.id,'group',g.name,'key',o.option_key,
                            'label',o.label,'deltaIdr',o.price_delta_idr)
                            ORDER BY g.name,g.id),'[]'::jsonb)
                        FROM menu_variant_options o
                        JOIN menu_variant_groups g ON g.id=o.group_id
                        WHERE o.id=ANY(CAST(? AS uuid[]))
                      )
                    ) END,?,?,?)
                """,orderId,item.menu().id(),item.menu().name(),
                item.selection().label(),item.selection().label(),
                item.selection().uuidArrayLiteral(),
                item.unitPrice(),item.quantity(),item.total());
            jdbc.update("""
                INSERT INTO order_stock_allocations(order_id,menu_item_id,quantity) VALUES (?,?,?)
                """,orderId,item.menu().id(),item.quantity());
        }
        jdbc.update("""
            INSERT INTO payment_intents(order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,?,?)
            """,orderId,selectedMethod,subtotal,
            "CASH".equals(selectedMethod)?"AWAITING_CASH":"PENDING");
        jdbc.update("""
            INSERT INTO order_idempotency(actor_id,scope,idempotency_key,request_hash,order_id,expires_at)
            VALUES (?,'CASH_CHECKOUT',?,?,?,now()+interval '7 days')
            """,guest.guestId(),key,hash,orderId);
        outbox.order(orderId,"ORDER_SUBMITTED");
        return new Checkout(orderId,"SUBMITTED",
            "CASH".equals(selectedMethod)?"AWAITING_CASH":"PENDING",subtotal);
    }

    private record Existing(UUID id,String hash,String status,long total,String payment) {}
    private record PricedLine(MenuRow menu,int quantity,long total,long unitPrice,
                              VariantPricingService.Selection selection) {}

    @Transactional(readOnly=true)
    public List<Checkout> ownOrders(GuestPrincipal guest) {
        return jdbc.query("""
            SELECT o.id,o.status,o.total_idr,p.aggregate_status FROM orders o
            JOIN payment_intents p ON p.order_id=o.id
            WHERE o.guest_id=? AND o.visit_id=? ORDER BY o.created_at DESC LIMIT 100
            """,(rs,n)->new Checkout(rs.getObject("id",UUID.class),rs.getString("status"),
                rs.getString("aggregate_status"),rs.getLong("total_idr")),
            guest.guestId(),guest.visitId());
    }

    private ApiFailureException failure(HttpStatus status,String code,String message) {
        return new ApiFailureException(status,code,message);
    }
}
