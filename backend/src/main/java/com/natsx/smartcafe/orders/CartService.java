package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.catalog.VariantPricingService;
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
    private final VariantPricingService variants;
    public CartService(JdbcTemplate jdbc,VariantPricingService variants) {
        this.jdbc=jdbc;
        this.variants=variants;
    }

    public record Line(UUID menuItemId,String name,long unitPriceIdr,int quantity,
                       long lineTotalIdr,boolean available,Long remainingQuantity,
                       UUID variantOptionId,String variantLabel,
                       List<UUID> variantOptionIds,List<String> variantLabels) {}
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
            variants.resolveSelections(line.menuItemId(),line.selectedOptionIds());
        }
        jdbc.update("DELETE FROM cart_lines WHERE cart_id=?",cart.id());
        for(var line:lines) {
            var ids=line.selectedOptionIds();
            jdbc.update("""
                INSERT INTO cart_lines(cart_id,menu_item_id,variant_option_id,quantity)
                VALUES (?,?,?,?)
                """,cart.id(),line.menuItemId(),ids.size()==1?ids.getFirst():null,line.quantity());
            for(var optionId:ids)jdbc.update("""
                INSERT INTO cart_line_variant_options(cart_id,menu_item_id,option_id)
                VALUES (?,?,?)
                """,cart.id(),line.menuItemId(),optionId);
        }
        jdbc.update("UPDATE carts SET version=version+1,updated_at=now() WHERE id=?",cart.id());
        return view(new CartRow(cart.id(),expectedVersion+1));
    }

    /** Must be called in a checkout transaction. Locks the cart against PUT. */
    public List<CashCheckoutService.Line> lockForCheckout(GuestPrincipal guest,long version) {
        var cart=findCart(guest,true);
        if(cart.version()!=version)throw conflict("Cart version is stale");
        var entries=jdbc.query("""
            SELECT menu_item_id,quantity FROM cart_lines WHERE cart_id=? ORDER BY menu_item_id
            """,(rs,n)->new Entry(rs.getObject("menu_item_id",UUID.class),
                rs.getInt("quantity")),cart.id());
        if(entries.isEmpty())throw invalid("Cart is empty");
        var lines=new java.util.ArrayList<CashCheckoutService.Line>();
        for(var entry:entries)lines.add(new CashCheckoutService.Line(entry.itemId(),
            entry.quantity(),null,selectedIds(cart.id(),entry.itemId())));
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

    private record Entry(UUID itemId,int quantity) {}
    private record PreviewRow(UUID itemId,String name,long price,int quantity,
                              Long remaining,boolean itemAvailable) {}

    private List<UUID> selectedIds(UUID cartId,UUID itemId) {
        return jdbc.query("""
            SELECT option_id FROM cart_line_variant_options
            WHERE cart_id=? AND menu_item_id=? ORDER BY option_id
            """,(rs,n)->rs.getObject("option_id",UUID.class),cartId,itemId);
    }

    private CartView view(CartRow cart) {
        var entries=jdbc.query("""
            SELECT l.menu_item_id,i.name,i.base_price_idr,l.quantity,a.limited_quantity,
                   (i.availability='AVAILABLE' AND c.active AND a.available
                   AND (a.limited_quantity IS NULL OR a.limited_quantity>=l.quantity)) AS can_order
            FROM cart_lines l JOIN menu_items i ON i.id=l.menu_item_id
            JOIN menu_categories c ON c.id=i.category_id
            JOIN item_availability a ON a.menu_item_id=i.id
            WHERE l.cart_id=? ORDER BY i.name,l.menu_item_id
            """,(rs,n)->new PreviewRow(rs.getObject("menu_item_id",UUID.class),
                rs.getString("name"),rs.getLong("base_price_idr"),rs.getInt("quantity"),
                rs.getObject("limited_quantity")==null?null:rs.getLong("limited_quantity"),
                rs.getBoolean("can_order")),cart.id());
        var lines=new java.util.ArrayList<Line>();
        long subtotal=0;
        for(var entry:entries) {
            var ids=selectedIds(cart.id(),entry.itemId());
            boolean available=entry.itemAvailable();
            long price=entry.price();
            List<String> labels=List.of();
            try {
                var selection=variants.resolveSelections(entry.itemId(),ids);
                price=Math.addExact(price,selection.totalDeltaIdr());
                if(price<0)throw new ArithmeticException("negative price");
                labels=selection.choices().stream().map(VariantPricingService.Choice::label).toList();
            }catch(ApiFailureException|ArithmeticException ex) {
                available=false;
                price=entry.price();
            }
            long extended=IdrMoney.lineTotal(price,entry.quantity());
            subtotal=Math.addExact(subtotal,extended);
            lines.add(new Line(entry.itemId(),entry.name(),price,entry.quantity(),extended,
                available,entry.remaining(),ids.size()==1?ids.getFirst():null,
                labels.size()==1?labels.getFirst():null,ids,labels));
        }
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
                || line.quantity()>20 || !seen.add(line.menuItemId())
                || line.selectedOptionIds().size()>8
                || line.selectedOptionIds().stream().anyMatch(id->id==null)
                || new HashSet<>(line.selectedOptionIds()).size()!=line.selectedOptionIds().size())
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
