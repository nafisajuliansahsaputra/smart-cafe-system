package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.identity.SecureToken;
import com.natsx.smartcafe.orders.CashCheckoutService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CartAndStockIntegrationTest {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("cart_stock_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired CashCheckoutService checkout;

    private record Customer(GuestPrincipal principal,String token) {}

    private UUID visit() {
        UUID staff=UUID.randomUUID(),table=UUID.randomUUID(),visit=UUID.randomUUID();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"fixture-"+staff,"synthetic-only");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),"cart-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,staff,"a".repeat(64));
        return visit;
    }

    private Customer guest(UUID visit) {
        UUID id=UUID.randomUUID();
        String token=SecureToken.generate();
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            id,visit,"Synthetic");
        jdbc.update("""
            INSERT INTO guest_sessions(token_hash,guest_id,visit_id,expires_at)
            VALUES (?,?,?,now()+interval '1 hour')
            """,SecureToken.sha256(token),id,visit);
        return new Customer(new GuestPrincipal(id,visit,SecureToken.sha256(token)),token);
    }

    private UUID menu(long price,long remaining) {
        UUID category=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category,"Synthetic","fixture-"+category);
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr)
            VALUES (?,?,?,?)
            """,item,category,"Synthetic Meal",price);
        if(remaining>=0)jdbc.update("""
            UPDATE item_availability SET limited_quantity=? WHERE menu_item_id=?
            """,remaining,item);
        return item;
    }

    private String field(String json,String name) {
        var matcher=Pattern.compile("\""+name+"\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(),"Missing field "+name);
        return matcher.group(1);
    }

    @Test void versionedCartIsOwnerScopedAndReplaysAfterConsumption() throws Exception {
        UUID visit=visit(),item=menu(25000,10);
        Customer a=guest(visit),b=guest(visit);
        var empty=mvc.perform(get("/api/v1/me/cart").header("Authorization","Bearer "+a.token()))
            .andReturn().getResponse();
        assertEquals(200,empty.getStatus());
        assertTrue(empty.getContentAsString().contains("\"version\":0"));
        assertEquals(401,mvc.perform(get("/api/v1/me/cart")).andReturn().getResponse().getStatus());
        String request="{\"expectedVersion\":0,\"lines\":[{\"menuItemId\":\""
            +item+"\",\"quantity\":2}]}";
        var replaced=mvc.perform(put("/api/v1/me/cart")
            .header("Authorization","Bearer "+a.token()).contentType(MediaType.APPLICATION_JSON)
            .content(request)).andReturn().getResponse();
        assertEquals(200,replaced.getStatus(),replaced.getContentAsString());
        assertTrue(replaced.getContentAsString().contains("\"estimatedSubtotalIdr\":50000"));
        assertTrue(replaced.getContentAsString().contains("\"version\":1"));
        assertEquals(409,mvc.perform(put("/api/v1/me/cart")
            .header("Authorization","Bearer "+a.token()).contentType(MediaType.APPLICATION_JSON)
            .content(request)).andReturn().getResponse().getStatus());
        var other=mvc.perform(get("/api/v1/me/cart")
            .header("Authorization","Bearer "+b.token())).andReturn().getResponse();
        assertEquals(200,other.getStatus());
        assertTrue(other.getContentAsString().contains("\"lines\":[]"));

        String key="cart-checkout-"+UUID.randomUUID();
        var ordered=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+a.token())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{\"cartVersion\":1}")).andReturn().getResponse();
        assertEquals(200,ordered.getStatus(),ordered.getContentAsString());
        String id=field(ordered.getContentAsString(),"id");
        assertTrue(ordered.getContentAsString().contains("\"totalIdr\":50000"));
        var consumed=mvc.perform(get("/api/v1/me/cart")
            .header("Authorization","Bearer "+a.token())).andReturn().getResponse();
        assertTrue(consumed.getContentAsString().contains("\"version\":2"));
        assertTrue(consumed.getContentAsString().contains("\"lines\":[]"));

        var replay=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+a.token())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{\"cartVersion\":1}")).andReturn().getResponse();
        assertEquals(200,replay.getStatus());
        assertEquals(id,field(replay.getContentAsString(),"id"));
        assertEquals(409,mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+a.token())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{\"cartVersion\":2}")).andReturn().getResponse().getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM orders WHERE guest_id=?",
            Integer.class,a.principal().guestId()));
        assertEquals(8,jdbc.queryForObject("""
            SELECT limited_quantity FROM item_availability WHERE menu_item_id=?
            """,Long.class,item));
        jdbc.update("UPDATE menu_items SET base_price_idr=55000 WHERE id=?",item);
        assertEquals(50000L,jdbc.queryForObject("""
            SELECT total_idr FROM orders WHERE id=?
            """,Long.class,UUID.fromString(id)));
    }

    @Test void twoGuestsCompetingForLastUnitOnlyCreateOneOrder() {
        UUID visit=visit(),item=menu(10000,1);
        var a=guest(visit);
        var b=guest(visit);
        var start=new java.util.concurrent.CountDownLatch(1);
        java.util.function.Supplier<Boolean> left=()->runCheckout(a,item,start);
        java.util.function.Supplier<Boolean> right=()->runCheckout(b,item,start);
        var first=CompletableFuture.supplyAsync(left);
        var second=CompletableFuture.supplyAsync(right);
        start.countDown();
        int successes=(first.join()?1:0)+(second.join()?1:0);
        assertEquals(1,successes);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM orders WHERE visit_id=?",
            Integer.class,visit));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT limited_quantity FROM item_availability WHERE menu_item_id=?
            """,Long.class,item));
    }

    private boolean runCheckout(Customer guest,UUID item,java.util.concurrent.CountDownLatch start) {
        try {
            start.await();
            checkout.checkout(guest.principal(),
                List.of(new CashCheckoutService.Line(item,1)),"stock-"+UUID.randomUUID());
            return true;
        } catch(ApiFailureException ex) {
            assertEquals("OUT_OF_STOCK",ex.code());
            return false;
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    @Test void failedMultiItemCheckoutRollsBackPreviousStockUpdates() {
        UUID visit=visit();
        Customer guest=guest(visit);
        UUID available=menu(5000,2),soldOut=menu(8000,0);
        ApiFailureException failure=assertThrows(ApiFailureException.class,()->
            checkout.checkout(guest.principal(),List.of(
                new CashCheckoutService.Line(available,1),
                new CashCheckoutService.Line(soldOut,1)),"rollback-"+UUID.randomUUID()));
        assertEquals("OUT_OF_STOCK",failure.code());
        assertEquals(2L,jdbc.queryForObject("""
            SELECT limited_quantity FROM item_availability WHERE menu_item_id=?
            """,Long.class,available));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM orders WHERE guest_id=?",
            Integer.class,guest.principal().guestId()));
    }

    private String staffSession(String role) {
        UUID staff=UUID.randomUUID();
        String token=SecureToken.generate();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"stock-"+staff,"synthetic-test-only");
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            staff,role);
        jdbc.update("""
            INSERT INTO staff_sessions(token_hash,staff_user_id,expires_at)
            VALUES (?,?,now()+interval '1 hour')
            """,SecureToken.sha256(token),staff);
        return token;
    }

    @Test void managerStockUpdatesRequireRoleVersionAndAudit() throws Exception {
        UUID item=menu(9000,2);
        String cashier=staffSession("CASHIER");
        String manager=staffSession("MANAGER");
        var initial=mvc.perform(get("/api/v1/admin/menu-items/"+item+"/availability")
            .header("Authorization","Bearer "+manager)).andReturn().getResponse();
        assertEquals(200,initial.getStatus());
        assertTrue(initial.getContentAsString().contains("\"limitedQuantity\":2"));
        String edit="{\"available\":true,\"limitedQuantity\":1,\"expectedVersion\":0,"
            +"\"reason\":\"Stock count corrected\"}";
        assertEquals(403,mvc.perform(patch("/api/v1/admin/menu-items/"+item+"/availability")
            .header("Authorization","Bearer "+cashier)
            .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andReturn().getResponse().getStatus());
        var updated=mvc.perform(patch("/api/v1/admin/menu-items/"+item+"/availability")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(edit)).andReturn().getResponse();
        assertEquals(200,updated.getStatus(),updated.getContentAsString());
        assertTrue(updated.getContentAsString().contains("\"version\":1"));
        assertEquals(409,mvc.perform(patch("/api/v1/admin/menu-items/"+item+"/availability")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(edit)).andReturn().getResponse().getStatus());
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE resource_id=? AND operation='MENU_STOCK_UPDATED'",
            Integer.class,item));
        String soldOut="{\"available\":true,\"limitedQuantity\":0,\"expectedVersion\":1,"
            +"\"reason\":\"Counted last stock\"}";
        assertEquals(200,mvc.perform(patch("/api/v1/admin/menu-items/"+item+"/availability")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(soldOut)).andReturn().getResponse().getStatus());
        var menuResponse=mvc.perform(get("/api/v1/menu")).andReturn().getResponse();
        assertEquals(200,menuResponse.getStatus());
        assertFalse(menuResponse.getContentAsString().contains(item.toString()));
        assertEquals(0L,jdbc.queryForObject("SELECT limited_quantity FROM item_availability "
            +"WHERE menu_item_id=?",Long.class,item));
    }

    @Test void managerCatalogLifecycleEnforcesRoleRevisionAndVisibility() throws Exception {
        String manager=staffSession("MANAGER");
        String cashier=staffSession("CASHIER");
        String slug="hot-drinks-"+UUID.randomUUID();
        String categoryPayload="{\"name\":\"Hot Drinks\",\"slug\":\""+slug+"\"}";
        assertEquals(403,mvc.perform(post("/api/v1/admin/menu-categories")
            .header("Authorization","Bearer "+cashier).contentType(MediaType.APPLICATION_JSON)
            .content(categoryPayload)).andReturn().getResponse().getStatus());
        var category=mvc.perform(post("/api/v1/admin/menu-categories")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(categoryPayload)).andReturn().getResponse();
        assertEquals(200,category.getStatus(),category.getContentAsString());
        UUID categoryId=UUID.fromString(field(category.getContentAsString(),"id"));
        assertEquals(409,mvc.perform(post("/api/v1/admin/menu-categories")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(categoryPayload)).andReturn().getResponse().getStatus());

        String itemPayload="{\"categoryId\":\""+categoryId+
            "\",\"name\":\"Cafe Latte\",\"priceIdr\":28000}";
        var created=mvc.perform(post("/api/v1/admin/menu-items")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(itemPayload)).andReturn().getResponse();
        assertEquals(200,created.getStatus(),created.getContentAsString());
        UUID itemId=UUID.fromString(field(created.getContentAsString(),"id"));
        assertTrue(created.getContentAsString().contains("\"availability\":\"HIDDEN\""));
        assertFalse(mvc.perform(get("/api/v1/menu")).andReturn().getResponse()
            .getContentAsString().contains(itemId.toString()));
        String edit="{\"categoryId\":\""+categoryId+
            "\",\"name\":\"Cafe Latte Large\",\"priceIdr\":35000,"+
            "\"availability\":\"AVAILABLE\",\"expectedRevision\":1}";
        assertEquals(403,mvc.perform(patch("/api/v1/admin/menu-items/"+itemId)
            .header("Authorization","Bearer "+cashier).contentType(MediaType.APPLICATION_JSON)
            .content(edit)).andReturn().getResponse().getStatus());
        var visible=mvc.perform(patch("/api/v1/admin/menu-items/"+itemId)
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(edit)).andReturn().getResponse();
        assertEquals(200,visible.getStatus(),visible.getContentAsString());
        assertTrue(visible.getContentAsString().contains("\"revision\":2"));
        assertEquals(409,mvc.perform(patch("/api/v1/admin/menu-items/"+itemId)
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(edit)).andReturn().getResponse().getStatus());
        String publicMenu=mvc.perform(get("/api/v1/menu")).andReturn().getResponse()
            .getContentAsString();
        assertTrue(publicMenu.contains(itemId.toString()));
        assertTrue(publicMenu.contains("35000"));

        String toggle="{\"active\":false}";
        assertEquals(200,mvc.perform(patch("/api/v1/admin/menu-categories/"+categoryId+"/active")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(toggle)).andReturn().getResponse().getStatus());
        assertFalse(mvc.perform(get("/api/v1/menu")).andReturn().getResponse()
            .getContentAsString().contains(itemId.toString()));
        assertEquals(3,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs WHERE actor_type='STAFF'
              AND resource_id IN (?,?) AND operation IN
                ('MENU_ITEM_CREATED','MENU_ITEM_CHANGED','CATEGORY_DISABLED')
            """,Integer.class,itemId,categoryId));
    }

    @Test void requiredVariantIsPricedServerSideAndSnapshotted() throws Exception {
        UUID visit=visit(),item=menu(20000,4);
        Customer guest=guest(visit);
        String manager=staffSession("MANAGER");
        String cashier=staffSession("CASHIER");
        String groupBody="{\"name\":\"Size\",\"required\":true}";
        assertEquals(403,mvc.perform(post("/api/v1/admin/menu-items/"+item+"/variant-group")
            .header("Authorization","Bearer "+cashier).contentType(MediaType.APPLICATION_JSON)
            .content(groupBody)).andReturn().getResponse().getStatus());
        var groupResponse=mvc.perform(post("/api/v1/admin/menu-items/"+item+"/variant-group")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(groupBody)).andReturn().getResponse();
        assertEquals(200,groupResponse.getStatus(),groupResponse.getContentAsString());
        UUID groupId=UUID.fromString(field(groupResponse.getContentAsString(),"id"));
        String optionBody="{\"key\":\"large\",\"label\":\"Large\",\"priceDeltaIdr\":5000}";
        var optionResponse=mvc.perform(post("/api/v1/admin/variant-groups/"+groupId+"/options")
            .header("Authorization","Bearer "+manager).contentType(MediaType.APPLICATION_JSON)
            .content(optionBody)).andReturn().getResponse();
        assertEquals(200,optionResponse.getStatus(),optionResponse.getContentAsString());
        UUID optionId=UUID.fromString(field(optionResponse.getContentAsString(),"id"));
        var published=mvc.perform(get("/api/v1/menu/"+item+"/variants"))
            .andReturn().getResponse();
        assertEquals(200,published.getStatus());
        assertTrue(published.getContentAsString().contains("\"required\":true"));
        assertTrue(published.getContentAsString().contains("Large"));
        assertEquals(409,mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+guest.token())
            .header("Idempotency-Key","no-variant-"+UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lines\":[{\"menuItemId\":\""+item+"\",\"quantity\":1}]}"))
            .andReturn().getResponse().getStatus());
        assertEquals(409,mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+guest.token())
            .header("Idempotency-Key","fake-variant-"+UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lines\":[{\"menuItemId\":\""+item+
                "\",\"quantity\":1,\"variantOptionId\":\""+UUID.randomUUID()+"\"}]}"))
            .andReturn().getResponse().getStatus());

        String cartJson="{\"expectedVersion\":0,\"lines\":[{\"menuItemId\":\""+item+
            "\",\"quantity\":2,\"variantOptionId\":\""+optionId+"\"}]}";
        var cart=mvc.perform(put("/api/v1/me/cart")
            .header("Authorization","Bearer "+guest.token())
            .contentType(MediaType.APPLICATION_JSON).content(cartJson))
            .andReturn().getResponse();
        assertEquals(200,cart.getStatus(),cart.getContentAsString());
        assertTrue(cart.getContentAsString().contains("\"estimatedSubtotalIdr\":50000"));
        assertTrue(cart.getContentAsString().contains("\"variantLabel\":\"Large\""));

        String idempotency="variant-cart-"+UUID.randomUUID();
        var order=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+guest.token())
            .header("Idempotency-Key",idempotency).contentType(MediaType.APPLICATION_JSON)
            .content("{\"cartVersion\":1}")).andReturn().getResponse();
        assertEquals(200,order.getStatus(),order.getContentAsString());
        UUID orderId=UUID.fromString(field(order.getContentAsString(),"id"));
        assertTrue(order.getContentAsString().contains("\"totalIdr\":50000"));
        assertEquals(25000L,jdbc.queryForObject("""
            SELECT unit_price_idr FROM order_lines WHERE order_id=?
            """,Long.class,orderId));
        String cashierToken=staffSession("CASHIER");
        String kitchenToken=staffSession("KITCHEN");
        assertEquals(200,mvc.perform(post("/api/v1/ops/orders/"+orderId+"/approve-cash")
            .header("Authorization","Bearer "+cashierToken))
            .andReturn().getResponse().getStatus());
        var kitchenTickets=mvc.perform(get("/api/v1/kitchen/tickets")
            .header("Authorization","Bearer "+kitchenToken)).andReturn().getResponse();
        assertEquals(200,kitchenTickets.getStatus(),kitchenTickets.getContentAsString());
        assertTrue(kitchenTickets.getContentAsString().contains("\"variantLabel\":\"Large\""));
        String snapshot=jdbc.queryForObject("""
            SELECT variant_snapshot::text FROM order_lines WHERE order_id=?
            """,String.class,orderId);
        assertTrue(snapshot.contains("Large"));
        assertTrue(snapshot.contains("5000"));
        assertTrue(snapshot.contains(optionId.toString()));

        String changed="{\"label\":\"Extra Large\",\"priceDeltaIdr\":9000,"
            +"\"active\":true,\"expectedRevision\":1}";
        var changedResponse=mvc.perform(patch("/api/v1/admin/variant-options/"+optionId)
            .header("Authorization","Bearer "+manager)
            .contentType(MediaType.APPLICATION_JSON).content(changed))
            .andReturn().getResponse();
        assertEquals(200,changedResponse.getStatus(),changedResponse.getContentAsString());
        assertEquals(409,mvc.perform(patch("/api/v1/admin/variant-options/"+optionId)
            .header("Authorization","Bearer "+manager)
            .contentType(MediaType.APPLICATION_JSON).content(changed))
            .andReturn().getResponse().getStatus());
        String detail=mvc.perform(get("/api/v1/me/orders/"+orderId)
            .header("Authorization","Bearer "+guest.token()))
            .andReturn().getResponse().getContentAsString();
        assertTrue(detail.contains("\"variantLabel\":\"Large\""));
        assertEquals(25000L,jdbc.queryForObject("""
            SELECT unit_price_idr FROM order_lines WHERE order_id=?
            """,Long.class,orderId));
        assertEquals(2L,jdbc.queryForObject("""
            SELECT limited_quantity FROM item_availability WHERE menu_item_id=?
            """,Long.class,item));
        assertEquals(200,mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+guest.token())
            .header("Idempotency-Key",idempotency)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"cartVersion\":1}")).andReturn().getResponse().getStatus());
    }

    @Test void multipleRequiredModifierGroupsPriceAtomicallyAndSnapshotSelections() throws Exception {
        UUID visit=visit(),item=menu(20000,6);
        Customer customer=guest(visit);
        String manager=staffSession("MANAGER");
        var size=mvc.perform(post("/api/v1/admin/menu-items/"+item+"/variant-group")
            .header("Authorization","Bearer "+manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Size\",\"required\":true}")).andReturn().getResponse();
        assertEquals(200,size.getStatus(),size.getContentAsString());
        UUID sizeGroup=UUID.fromString(field(size.getContentAsString(),"id"));
        var milk=mvc.perform(post("/api/v1/admin/menu-items/"+item+"/variant-group")
            .header("Authorization","Bearer "+manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Milk\",\"required\":true}")).andReturn().getResponse();
        assertEquals(200,milk.getStatus(),milk.getContentAsString());
        UUID milkGroup=UUID.fromString(field(milk.getContentAsString(),"id"));
        var extras=mvc.perform(post("/api/v1/admin/menu-items/"+item+"/variant-group")
            .header("Authorization","Bearer "+manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Extras\",\"required\":false}")).andReturn().getResponse();
        assertEquals(200,extras.getStatus(),extras.getContentAsString());

        UUID large=createVariantOption(sizeGroup,manager,"large","Large",5000);
        UUID medium=createVariantOption(sizeGroup,manager,"medium","Medium",1000);
        UUID oat=createVariantOption(milkGroup,manager,"oat","Oat milk",2000);
        String groups=mvc.perform(get("/api/v1/menu/"+item+"/variant-groups"))
            .andReturn().getResponse().getContentAsString();
        assertTrue(groups.contains("Size"));
        assertTrue(groups.contains("Milk"));
        assertTrue(groups.contains("Extras"));
        assertEquals(3,jdbc.queryForObject(
            "SELECT count(*) FROM menu_variant_groups WHERE menu_item_id=?",Integer.class,item));

        String prefix="{\"lines\":[{\"menuItemId\":\""+item+"\",\"quantity\":2,";
        String missing=prefix+"\"variantOptionIds\":[\""+large+"\"]}]}";
        assertEquals(409,mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+customer.token())
            .header("Idempotency-Key","missing-milk-"+UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(missing))
            .andReturn().getResponse().getStatus());

        String twice=prefix+"\"variantOptionIds\":[\""+large+"\",\""+medium+
            "\",\""+oat+"\"]}]}";
        assertEquals(409,mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+customer.token())
            .header("Idempotency-Key","two-sizes-"+UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(twice))
            .andReturn().getResponse().getStatus());

        String cart="{\"expectedVersion\":0,\"lines\":[{\"menuItemId\":\""+item+
            "\",\"quantity\":2,\"variantOptionIds\":[\""+oat+"\",\""+large+"\"]}]}";
        var saved=mvc.perform(put("/api/v1/me/cart")
            .header("Authorization","Bearer "+customer.token())
            .contentType(MediaType.APPLICATION_JSON).content(cart)).andReturn().getResponse();
        assertEquals(200,saved.getStatus(),saved.getContentAsString());
        assertTrue(saved.getContentAsString().contains("\"estimatedSubtotalIdr\":54000"));
        assertTrue(saved.getContentAsString().contains("Oat milk"));
        assertEquals(2,jdbc.queryForObject("""
            SELECT count(*) FROM cart_line_variant_options
            WHERE menu_item_id=?
            """,Integer.class,item));

        String key="multi-modifier-"+UUID.randomUUID();
        var ordered=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+customer.token())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{\"cartVersion\":1}")).andReturn().getResponse();
        assertEquals(200,ordered.getStatus(),ordered.getContentAsString());
        UUID id=UUID.fromString(field(ordered.getContentAsString(),"id"));
        assertTrue(ordered.getContentAsString().contains("\"totalIdr\":54000"));
        assertEquals(27000L,jdbc.queryForObject(
            "SELECT unit_price_idr FROM order_lines WHERE order_id=?",Long.class,id));
        assertEquals(2,jdbc.queryForObject("""
            SELECT jsonb_array_length(variant_snapshot->'options')
            FROM order_lines WHERE order_id=?
            """,Integer.class,id));
        assertEquals(4L,jdbc.queryForObject(
            "SELECT limited_quantity FROM item_availability WHERE menu_item_id=?",Long.class,item));
        String detail=mvc.perform(get("/api/v1/me/orders/"+id)
            .header("Authorization","Bearer "+customer.token()))
            .andReturn().getResponse().getContentAsString();
        assertTrue(detail.contains("Large"));
        assertTrue(detail.contains("Oat milk"));
        assertEquals(200,mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+customer.token())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{\"cartVersion\":1}")).andReturn().getResponse().getStatus());

        // Direct checkout uses the same canonical fingerprint regardless of option ordering.
        String direct=prefix+"\"variantOptionIds\":[\""+large+"\",\""+oat+"\"]}]}";
        String reversed=prefix+"\"variantOptionIds\":[\""+oat+"\",\""+large+"\"]}]}";
        String otherKey="order-multi-"+UUID.randomUUID();
        var first=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+customer.token())
            .header("Idempotency-Key",otherKey).contentType(MediaType.APPLICATION_JSON)
            .content(direct)).andReturn().getResponse();
        assertEquals(200,first.getStatus(),first.getContentAsString());
        var retry=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+customer.token())
            .header("Idempotency-Key",otherKey).contentType(MediaType.APPLICATION_JSON)
            .content(reversed)).andReturn().getResponse();
        assertEquals(200,retry.getStatus(),retry.getContentAsString());
        assertEquals(field(first.getContentAsString(),"id"),field(retry.getContentAsString(),"id"));
    }

    private UUID createVariantOption(UUID group,String manager,String key,String label,long delta)
        throws Exception {
        var result=mvc.perform(post("/api/v1/admin/variant-groups/"+group+"/options")
            .header("Authorization","Bearer "+manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"key\":\""+key+"\",\"label\":\""+label+
                "\",\"priceDeltaIdr\":"+delta+"}")).andReturn().getResponse();
        assertEquals(200,result.getStatus(),result.getContentAsString());
        return UUID.fromString(field(result.getContentAsString(),"id"));
    }
}
