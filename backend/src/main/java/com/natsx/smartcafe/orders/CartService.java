package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cart preview is advisory; stock is validated and consumed in checkout. */
@Service
public class CartService {
    private final JdbcTemplate jdbc;
    public CartService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Line(UUID menuItemId,String name,long unitPriceIdr,int quantity,
                       long lineTotalIdr,boolean available,Long remainingQuantity) {}
    public record CartView(long version,long estimatedSubtotalIdr,List<Line> lines) {}
    private record CartRow(UUID id,long version) {}

    @Transactional
    public CartView get(GuestPrincipal guest) {
        ensureVisit(guest);
        return view(findCart(guest,false));
    }

    @Transactional
    public CartView replace(GuestPrincipal guest,long expectedVersion,List<CashCheckoutService.Line> lines) {
        if(expectedVersion<0)throw invalid("Invalid cart version");
        validate(lines);
        ensureVisit(guest);
        var cart=findCart(guest,true);
        if(cart.version()!=expectedVersion)throw conflict("Cart version is stale");
        for(var line:lines) {
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM menu_items i
                JOIN menu_categories c ON c.id=i.category_id
                JOIN item_availability a ON a.menu_item_id=i.id
                WHERE i.id=? AND i.availability='AVAILABLE' AND c.active
                  AND a.available AND (a.limited_quantity IS NULL OR a.limited_quantity>0)
                """,Integer.class,line.menuItemId());
            if(count==null || count==0)throw outOfStock();
        }
        jdbc.update("DELETE FROM cart_lines WHERE cart_id=?",cart.id());
        for(var line:lines)jdbc.update("""
            INSERT INTO cart_lines(cart_id,menu_item_id,quantity) VALUES (?,?,?)
            """,cart.id(),line.menuItemId(),line.quantity());
        jdbc.update("UPDATE carts SET version=version+1,updated_at=now() WHERE id=?",cart.id());
        return view(new CartRow(cart.id(),expectedVersion+1));
    }

    /** Must be called in a checkout transaction. Locks the cart against PUT. */
    public List<CashCheckoutService.Line> lockForCheckout(GuestPrincipal guest,long version) {
        var cart=findCart(guest,true);
        if(cart.version()!=version)throw conflict("Cart version is stale");
        var lines=jdbc.query("""
            SELECT menu_item_id,quantity FROM cart_lines WHERE cart_id=? ORDER BY menu_item_id
            """,(rs,n)->new CashCheckoutService.Line(rs.getObject("menu_item_id",UUID.class),
                rs.getInt("quantity")),cart.id());
        if(lines.isEmpty())throw invalid("Cart is empty");
        return lines;
    }

    /** Called only after a successful checkout in the same transaction. */
    public void consume(GuestPrincipal guest,long version) {
        jdbc.update("""
            DELETE FROM cart_lines WHERE cart_id IN (
                SELECT id FROM carts WHERE guest_id=? AND visit_id=? AND version=?)
            """,guest.guestId(),guest.visitId(),version);
        int updated=jdbc.update("""
            UPDATE carts SET version=version+1,updated_at=now()
            WHERE guest_id=? AND visit_id=? AND version=?
            """,guest.guestId(),guest.visitId(),version);
        if(updated!=1)throw new IllegalStateException("Cart changed during checkout");
    }

    private CartRow findCart(GuestPrincipal guest,boolean lock) {
        jdbc.update("""
            INSERT INTO carts(guest_id,visit_id) VALUES (?,?) ON CONFLICT (guest_id) DO NOTHING
            """,guest.guestId(),guest.visitId());
        var rows=jdbc.query("SELECT id,version FROM carts WHERE guest_id=? AND visit_id=?"
            +(lock?" FOR UPDATE":""),(rs,n)->new CartRow(
                rs.getObject("id",UUID.class),rs.getLong("version")),guest.guestId(),guest.visitId());
        if(rows.isEmpty())throw conflict("Cart not available");
        return rows.getFirst();
    }

    private CartView view(CartRow cart) {
        var lines=jdbc.query("""
            SELECT l.menu_item_id,i.name,i.base_price_idr,l.quantity,a.limited_quantity,
              (i.availability='AVAILABLE' AND c.active AND a.available
                AND (a.limited_quantity IS NULL OR a.limited_quantity>=l.quantity)) AS can_order
            FROM cart_lines l JOIN menu_items i ON i.id=l.menu_item_id
            JOIN menu_categories c ON c.id=i.category_id
            JOIN item_availability a ON a.menu_item_id=i.id WHERE l.cart_id=?
            ORDER BY i.name,l.menu_item_id
            """,(rs,n)->{
                long price=rs.getLong("base_price_idr");
                int quantity=rs.getInt("quantity");
                Long remaining=rs.getObject("limited_quantity")==null
                    ?null:rs.getLong("limited_quantity");
                return new Line(rs.getObject("menu_item_id",UUID.class),rs.getString("name"),
                    price,quantity,IdrMoney.lineTotal(price,quantity),
                    rs.getBoolean("can_order"),remaining);
            },cart.id());
        long subtotal=0;
        for(var line:lines)subtotal=Math.addExact(subtotal,line.lineTotalIdr());
        return new CartView(cart.version(),subtotal,lines);
    }

    private void ensureVisit(GuestPrincipal guest) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM table_visits v JOIN visit_guests g ON g.visit_id=v.id
            WHERE g.id=? AND v.id=? AND v.status='ACTIVE'
            """,Integer.class,guest.guestId(),guest.visitId());
        if(count==null || count==0)throw new ApiFailureException(
            HttpStatus.CONFLICT,"SESSION_CLOSED","Table visit ended");
    }

    public static void validate(List<CashCheckoutService.Line> lines) {
        if(lines==null || lines.size()>30)throw invalid("Maximum 30 cart lines");
        var seen=new HashSet<UUID>();
        for(var line:lines) {
            if(line==null || line.menuItemId()==null || line.quantity()<1
                || line.quantity()>20 || !seen.add(line.menuItemId()))
                throw invalid("Invalid or duplicate cart line");
        }
    }

    private static ApiFailureException invalid(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);
    }
    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",message);
    }
    private ApiFailureException outOfStock() {
        return new ApiFailureException(HttpStatus.CONFLICT,"OUT_OF_STOCK","Menu item unavailable");
    }
}
