package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.natsx.smartcafe.identity.SecureToken;
import java.util.UUID;
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

/**
 * Only this synthetic container enables two independent sandbox flags.
 * Default deployments keep QRIS/VA creation disabled; no provider funds exist.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments",
    "scs.payments.sandbox-digital-checkout-enabled=true",
    "scs.payments.environment=sandbox"
})
class SandboxDigitalCheckoutIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("scs_checkout_sandbox").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",postgres::getJdbcUrl);
        properties.add("spring.datasource.username",postgres::getUsername);
        properties.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.natsx.smartcafe.orders.CartService carts;
    @Autowired com.natsx.smartcafe.common.events.OutboxEvents outbox;
    @Autowired com.natsx.smartcafe.catalog.VariantPricingService variants;

    private record Session(UUID guest,UUID visit,String token,UUID item) {}

    private Session guest() {
        UUID staff=UUID.randomUUID(),table=UUID.randomUUID(),visit=UUID.randomUUID();
        UUID guest=UUID.randomUUID(),cat=UUID.randomUUID(),item=UUID.randomUUID();
        String token=SecureToken.generate();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"m3-sandbox-"+staff,"test-only-invalid-password-hash");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),"m3-sandbox-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,staff,"0".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic Guest");
        jdbc.update("""
            INSERT INTO guest_sessions(token_hash,guest_id,visit_id,expires_at)
            VALUES (?,?,?,now()+interval '1 hour')
            """,SecureToken.sha256(token),guest,visit);
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            cat,"M3 Demo Food","m3-demo-"+cat);
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr)
            VALUES (?,?,?,?)
            """,item,cat,"Synthetic Sandbox Meal",27000);
        return new Session(guest,visit,token,item);
    }

    private String submit(Session guest,String method,String key) throws Exception {
        String body="{\"paymentMethod\":\""+method+"\",\"lines\":[{\"menuItemId\":\""+
            guest.item()+"\",\"quantity\":1}]}";
        var response=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+guest.token())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content(body)).andReturn().getResponse();
        if(response.getStatus()!=200) {
            return response.getStatus()+":"+response.getContentAsString();
        }
        return response.getContentAsString();
    }

    private UUID order(String response) {
        var matcher=Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(response);
        assertTrue(matcher.find(),response);
        return UUID.fromString(matcher.group(1));
    }

    private void assertUnpaid(UUID order,String method) {
        assertEquals(method,jdbc.queryForObject("""
            SELECT method_selected FROM payment_intents WHERE order_id=?
            """,String.class,order));
        assertEquals("PENDING",jdbc.queryForObject("""
            SELECT aggregate_status FROM payment_intents WHERE order_id=?
            """,String.class,order));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT amount_paid_idr FROM payment_intents WHERE order_id=?
            """,Long.class,order));
        assertEquals(27000L,jdbc.queryForObject("""
            SELECT amount_due_idr FROM payment_intents WHERE order_id=?
            """,Long.class,order));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM receipts WHERE order_id=?
            """,Integer.class,order));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM kitchen_tickets WHERE order_id=?
            """,Integer.class,order));
        // A sandbox-pending order does NOT fabricate payment attempt creation.
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts a
            JOIN payment_intents p ON p.id=a.payment_intent_id WHERE p.order_id=?
            """,Integer.class,order));
    }

    @Test void qrisCreatesPendingOwnedOrderAndIdempotencyCannotSwitchMethod() throws Exception {
        var alice=guest();
        var bob=guest();
        String key="digital-checkout-"+UUID.randomUUID();
        var created=submit(alice,"QRIS",key);
        UUID order=order(created);
        assertTrue(created.contains("\"paymentStatus\":\"PENDING\""),created);
        assertTrue(created.contains("\"status\":\"SUBMITTED\""),created);
        assertUnpaid(order,"QRIS");
        assertEquals(order,order(submit(alice,"QRIS",key)));
        assertTrue(submit(alice,"VA",key).startsWith("409:"));
        assertTrue(submit(alice,"CASH",key).startsWith("409:"));
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM orders WHERE guest_id=?",Integer.class,alice.guest()));
        var own=mvc.perform(get("/api/v1/me/orders/"+order)
            .header("Authorization","Bearer "+alice.token())).andReturn().getResponse();
        assertEquals(200,own.getStatus());
        assertTrue(own.getContentAsString().contains("PENDING"));
        assertEquals(404,mvc.perform(get("/api/v1/me/orders/"+order)
            .header("Authorization","Bearer "+bob.token()))
            .andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/v1/me/orders/"+order+"/receipt")
            .header("Authorization","Bearer "+alice.token()))
            .andReturn().getResponse().getStatus());
    }

    @Test void vaCartCheckoutAndOldCashMethodRemainDistinct() throws Exception {
        var guest=guest();
        var stored=mvc.perform(put("/api/v1/me/cart")
            .header("Authorization","Bearer "+guest.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":0,\"lines\":[{\"menuItemId\":\""+
                guest.item()+"\",\"quantity\":1}]}"))
            .andReturn().getResponse();
        assertEquals(200,stored.getStatus(),stored.getContentAsString());
        var m=Pattern.compile("\"version\":(\\d+)").matcher(stored.getContentAsString());
        assertTrue(m.find(),stored.getContentAsString());
        long version=Long.parseLong(m.group(1));
        String key="va-cart-"+UUID.randomUUID();
        String body="{\"cartVersion\":"+version+",\"paymentMethod\":\"VA\"}";
        var created=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+guest.token())
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
        assertEquals(200,created.getStatus(),created.getContentAsString());
        UUID order=order(created.getContentAsString());
        assertUnpaid(order,"VA");
        var repeated=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+guest.token())
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
        assertEquals(200,repeated.getStatus(),repeated.getContentAsString());
        assertEquals(order,order(repeated.getContentAsString()));
        var altered=mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer "+guest.token())
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"cartVersion\":"+version+",\"paymentMethod\":\"CASH\"}"))
            .andReturn().getResponse();
        assertEquals(409,altered.getStatus());
        var other=guest();
        String cash=submit(other,"CASH","cash-order-"+UUID.randomUUID());
        UUID cashId=order(cash);
        assertNotEquals(order,cashId);
        assertEquals("AWAITING_CASH",jdbc.queryForObject("""
            SELECT aggregate_status FROM payment_intents WHERE order_id=?
            """,String.class,cashId));
    }


    @Test void environmentAndEnableFlagMustBothBePresent() {
        var guest=guest();
        var principal=new com.natsx.smartcafe.identity.GuestPrincipal(
            guest.guest(),guest.visit(),SecureToken.sha256(guest.token()));
        var lines=java.util.List.of(
            new com.natsx.smartcafe.orders.CashCheckoutService.Line(guest.item(),1));
        // Construct misconfigured variants without an HTTP endpoint or bean.
        // Both are rejected before any stock or order mutation.
        for(var service:java.util.List.of(
            new com.natsx.smartcafe.orders.CashCheckoutService(
                jdbc,carts,outbox,variants,true,"production"),
            new com.natsx.smartcafe.orders.CashCheckoutService(
                jdbc,carts,outbox,variants,false,"sandbox"))) {
            for(String method:java.util.List.of("QRIS","VA")) {
                var ex=org.junit.jupiter.api.Assertions.assertThrows(
                    com.natsx.smartcafe.common.api.ApiFailureException.class,
                    ()->service.checkout(principal,lines,"sandbox-gate-"+method,method));
                assertEquals(403,ex.status().value());
            }
        }
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM orders WHERE guest_id=?",Integer.class,guest.guest()));
    }


    @Test void ownedFakeAttemptApiDoesNotSettleAndRejectsForeignOrDuplicate() throws Exception {
        var alice=guest();
        var bob=guest();
        var orderId=order(submit(alice,"QRIS","attempt-checkout-"+UUID.randomUUID()));
        String url="/api/v1/me/orders/"+orderId+"/sandbox-attempts";
        String key="attempt-"+UUID.randomUUID();
        String body="{\"method\":\"QRIS\"}";

        var before=mvc.perform(get(url).header("Authorization","Bearer "+alice.token()))
            .andReturn().getResponse();
        assertEquals(200,before.getStatus(),before.getContentAsString());
        assertTrue(before.getContentAsString().contains("\"data\":[]"));
        assertTrue(before.getHeader("Cache-Control").contains("no-store"));
        assertEquals(401,mvc.perform(post(url)
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(post(url)
            .header("Authorization","Bearer "+bob.token())
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get(url)
            .header("Authorization","Bearer "+bob.token()))
            .andReturn().getResponse().getStatus());
        assertEquals(409,mvc.perform(post(url)
            .header("Authorization","Bearer "+alice.token())
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"VA\"}"))
            .andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(post(url)
            .header("Authorization","Bearer "+alice.token())
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\"}"))
            .andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(post(url)
            .header("Authorization","Bearer "+alice.token())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus());

        var created=mvc.perform(post(url)
            .header("Authorization","Bearer "+alice.token())
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
        assertEquals(200,created.getStatus(),created.getContentAsString());
        assertTrue(created.getHeader("Cache-Control").contains("no-store"));
        assertTrue(created.getContentAsString().contains("FAKE_QRIS_NOT_PAYABLE"));
        assertTrue(created.getContentAsString().contains("SANDBOX_FAKE_NOT_MONEY"));
        assertTrue(created.getContentAsString().contains("\"state\":\"PENDING\""));
        String reference=Pattern.compile("FAKE_[a-f0-9]{32}")
            .matcher(created.getContentAsString()).results().findFirst()
            .orElseThrow().group();
        var replay=mvc.perform(post(url)
            .header("Authorization","Bearer "+alice.token())
            .header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
        assertEquals(200,replay.getStatus(),replay.getContentAsString());
        assertTrue(replay.getContentAsString().contains(reference));
        assertEquals(409,mvc.perform(post(url)
            .header("Authorization","Bearer "+alice.token())
            .header("Idempotency-Key","different-"+UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus());
        var visible=mvc.perform(get(url)
            .header("Authorization","Bearer "+alice.token()))
            .andReturn().getResponse();
        assertEquals(200,visible.getStatus());
        assertTrue(visible.getContentAsString().contains(reference));
        assertTrue(visible.getContentAsString().contains("NON_PAYABLE") ||
            visible.getContentAsString().contains("NOT_PAYABLE"));
        assertTrue(!visible.getContentAsString().contains(key));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts d
            JOIN payment_intents p ON p.id=d.payment_intent_id WHERE p.order_id=?
            """,Integer.class,orderId));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT amount_paid_idr FROM payment_intents WHERE order_id=?
            """,Long.class,orderId));
        assertEquals("PENDING",jdbc.queryForObject("""
            SELECT aggregate_status FROM payment_intents WHERE order_id=?
            """,String.class,orderId));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM receipts WHERE order_id=?
            """,Integer.class,orderId));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM kitchen_tickets WHERE order_id=?
            """,Integer.class,orderId));

        var cash=guest();
        var cashOrder=order(submit(cash,"CASH","attempt-cash-"+UUID.randomUUID()));
        assertEquals(409,mvc.perform(post("/api/v1/me/orders/"+cashOrder+"/sandbox-attempts")
            .header("Authorization","Bearer "+cash.token())
            .header("Idempotency-Key","cash-attempt-"+UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus());
        assertEquals(409,mvc.perform(get("/api/v1/me/orders/"+cashOrder+"/sandbox-attempts")
            .header("Authorization","Bearer "+cash.token()))
            .andReturn().getResponse().getStatus());
    }

    @Test void invalidMethodDoesNotCreateOrderOrReserveStock() throws Exception {
        var guest=guest();
        assertTrue(submit(guest,"CARD","bad-method-123456").startsWith("400:"));
        assertTrue(submit(guest,"qris","wrong-case-123456").startsWith("400:"));
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM orders WHERE guest_id=?",Integer.class,guest.guest()));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM order_stock_allocations
            WHERE menu_item_id=?
            """,Integer.class,guest.item()));
    }
}
