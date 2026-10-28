package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.orders.CashCheckoutService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class CashOrderFlowIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_cash").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @Autowired CashCheckoutService checkout;

    private String value(String json,String field) {
        var m=Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(),"Missing " + field + " in " + json);
        return m.group(1);
    }

    private String staff(String role) throws Exception {
        UUID id=UUID.randomUUID();
        String login="cash-" + id;
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id,login,passwords.encode("synthetic-password-123"));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",id,role);
        var response=mvc.perform(post("/api/v1/staff/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"login\":\"" + login + "\",\"password\":\"synthetic-password-123\"}"))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        return value(response.getContentAsString(),"accessToken");
    }

    private record Visit(UUID id,String alias,String proof) {}
    private Visit open(String waiter) throws Exception {
        UUID table=UUID.randomUUID();
        String alias="cash-" + table;
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),alias,4);
        var response=mvc.perform(post("/api/v1/waiter/table-visits")
            .header("Authorization","Bearer " + waiter)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableId\":\"" + table + "\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        String body=response.getContentAsString();
        return new Visit(UUID.fromString(value(body,"id")),alias,value(body,"joinProof"));
    }

    private record Guest(UUID id,String token) {}
    private Guest join(Visit visit,String name) throws Exception {
        var response=mvc.perform(post("/api/v1/table-visits/join")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableAlias\":\"" + visit.alias() +
                "\",\"joinProof\":\"" + visit.proof() +
                "\",\"displayName\":\"" + name + "\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        return new Guest(UUID.fromString(value(response.getContentAsString(),"guestId")),
            value(response.getContentAsString(),"accessToken"));
    }

    private UUID item(long price) {
        UUID category=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category,"Test Food","category-"+category);
        jdbc.update("INSERT INTO menu_items(id,category_id,name,base_price_idr) VALUES (?,?,?,?)",
            id,category,"Synthetic Meal",price);
        return id;
    }

    private org.springframework.mock.web.MockHttpServletResponse submit(
        Guest guest,UUID menuItem,int quantity,String key) throws Exception {
        return mvc.perform(post("/api/v1/orders")
            .header("Authorization","Bearer " + guest.token())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{\"lines\":[{\"menuItemId\":\"" + menuItem +
                "\",\"quantity\":" + quantity + "}]}")).andReturn().getResponse();
    }

    @Test void completeCashFlowSeparatesApprovalServiceAndRealTender() throws Exception {
        String waiter=staff("WAITER"),cashier=staff("CASHIER"),kitchen=staff("KITCHEN");
        var visit=open(waiter);
        var a=join(visit,"Synthetic A");
        var b=join(visit,"Synthetic B");
        UUID menuItem=item(28000);
        String key="checkout-" + UUID.randomUUID();
        var first=submit(a,menuItem,2,key);
        assertEquals(200,first.getStatus(),first.getContentAsString());
        String json=first.getContentAsString();
        UUID orderId=UUID.fromString(value(json,"id"));
        assertTrue(json.contains("AWAITING_CASH"));
        assertTrue(json.contains("56000"));
        assertEquals(orderId,UUID.fromString(value(submit(a,menuItem,2,key).getContentAsString(),"id")));
        assertEquals(409,submit(a,menuItem,3,key).getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM orders WHERE guest_id=?",
            Integer.class,a.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM orders WHERE guest_id=?",
            Integer.class,b.id()));
        var own=mvc.perform(get("/api/v1/me/orders")
            .header("Authorization","Bearer " + b.token())).andReturn().getResponse();
        assertEquals(200,own.getStatus());
        assertTrue(own.getContentAsString().contains("\"data\":[]"));

        jdbc.update("UPDATE menu_items SET base_price_idr=35000 WHERE id=?",menuItem);
        assertEquals(56000,jdbc.queryForObject("SELECT total_idr FROM orders WHERE id=?",
            Long.class,orderId));
        assertThrows(DataAccessException.class,()->jdbc.update(
            "UPDATE orders SET total_idr=57000 WHERE id=?",orderId));
        assertEquals(403,mvc.perform(get("/api/v1/ops/orders")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get("/api/v1/ops/orders")
            .header("Authorization","Bearer " + cashier)).andReturn().getResponse().getStatus());
        var filtered=mvc.perform(get("/api/v1/ops/orders")
            .param("status","SUBMITTED").param("paymentStatus","AWAITING_CASH")
            .param("orderId",orderId.toString())
            .header("Authorization","Bearer " + cashier)).andReturn().getResponse();
        assertEquals(200,filtered.getStatus());
        assertTrue(filtered.getContentAsString().contains(orderId.toString()));
        assertEquals(400,mvc.perform(get("/api/v1/ops/orders").param("status","UNRECOGNIZED")
            .header("Authorization","Bearer " + cashier)).andReturn().getResponse().getStatus());
        var detail=mvc.perform(get("/api/v1/ops/orders/" + orderId)
            .header("Authorization","Bearer " + cashier)).andReturn().getResponse();
        assertEquals(200,detail.getStatus());
        assertTrue(detail.getContentAsString().contains("Synthetic Meal"));
        assertEquals(403,mvc.perform(get("/api/v1/ops/orders/" + orderId)
            .header("Authorization","Bearer " + kitchen)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get("/api/v1/kitchen/tickets")
            .header("Authorization","Bearer " + kitchen)).andReturn().getResponse().getStatus());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM kitchen_tickets WHERE order_id=?",
            Integer.class,orderId));
        assertEquals(409,mvc.perform(post("/api/v1/waiter/table-visits/" + visit.id() + "/close")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse().getStatus());

        var approved=mvc.perform(post("/api/v1/ops/orders/" + orderId + "/approve-cash")
            .header("Authorization","Bearer " + cashier)).andReturn().getResponse();
        assertEquals(200,approved.getStatus(),approved.getContentAsString());
        assertEquals(409,mvc.perform(post("/api/v1/ops/orders/" + orderId + "/approve-cash")
            .header("Authorization","Bearer " + cashier)).andReturn().getResponse().getStatus());
        UUID ticket=jdbc.queryForObject("SELECT id FROM kitchen_tickets WHERE order_id=?",
            UUID.class,orderId);
        UUID intent=jdbc.queryForObject("SELECT id FROM payment_intents WHERE order_id=?",
            UUID.class,orderId);
        assertEquals("AWAITING_CASH",jdbc.queryForObject(
            "SELECT aggregate_status FROM payment_intents WHERE id=?",String.class,intent));
        assertEquals(403,mvc.perform(post("/api/v1/kitchen/tickets/" + ticket + "/start")
            .header("Authorization","Bearer " + cashier)).andReturn().getResponse().getStatus());

        assertEquals(200,mvc.perform(post("/api/v1/kitchen/tickets/" + ticket + "/start")
            .header("Authorization","Bearer " + kitchen)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/kitchen/tickets/" + ticket + "/ready")
            .header("Authorization","Bearer " + kitchen)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/waiter/orders/" + orderId + "/served")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse().getStatus());
        assertEquals("AWAITING_CASH",jdbc.queryForObject(
            "SELECT aggregate_status FROM payment_intents WHERE id=?",String.class,intent));

        String tenderKey="tender-" + UUID.randomUUID();
        var tender=mvc.perform(post("/api/v1/ops/payments/" + intent + "/cash-tenders")
            .header("Authorization","Bearer " + waiter).header("Idempotency-Key",tenderKey)
            .contentType(MediaType.APPLICATION_JSON).content("{\"amountReceivedIdr\":60000}"))
            .andReturn().getResponse();
        assertEquals(200,tender.getStatus(),tender.getContentAsString());
        assertTrue(tender.getContentAsString().contains("\"changeGivenIdr\":4000"));
        UUID receipt=UUID.fromString(value(tender.getContentAsString(),"receiptId"));
        var replay=mvc.perform(post("/api/v1/ops/payments/" + intent + "/cash-tenders")
            .header("Authorization","Bearer " + cashier).header("Idempotency-Key",tenderKey)
            .contentType(MediaType.APPLICATION_JSON).content("{\"amountReceivedIdr\":60000}"))
            .andReturn().getResponse();
        assertEquals(200,replay.getStatus(),replay.getContentAsString());
        assertEquals(409,mvc.perform(post("/api/v1/ops/payments/" + intent + "/cash-tenders")
            .header("Authorization","Bearer " + cashier).header("Idempotency-Key",tenderKey)
            .contentType(MediaType.APPLICATION_JSON).content("{\"amountReceivedIdr\":61000}"))
            .andReturn().getResponse().getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cash_tenders WHERE payment_intent_id=?",
            Integer.class,intent));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM receipts WHERE order_id=?",
            Integer.class,orderId));
        assertEquals("PAID",jdbc.queryForObject(
            "SELECT aggregate_status FROM payment_intents WHERE id=?",String.class,intent));
        assertEquals(200,mvc.perform(get("/api/v1/me/receipts/" + receipt)
            .header("Authorization","Bearer " + a.token())).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/v1/me/receipts/" + receipt)
            .header("Authorization","Bearer " + b.token())).andReturn().getResponse().getStatus());
        assertThrows(DataAccessException.class,()->jdbc.update(
            "UPDATE receipts SET total_idr=1 WHERE id=?",receipt));
        assertEquals(200,mvc.perform(post("/api/v1/waiter/table-visits/" + visit.id() + "/close")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/me/orders")
            .header("Authorization","Bearer " + a.token())).andReturn().getResponse().getStatus());
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=?",
            Integer.class,orderId)>0);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?",
            Integer.class,orderId)>=5);
    }

    @Test void parallelCheckoutWithSameKeyYieldsExactlyOneOrder() throws Exception {
        String waiter=staff("WAITER");
        var visit=open(waiter);
        var guest=join(visit,"Concurrent Guest");
        UUID menuItem=item(10000);
        GuestPrincipal principal=new GuestPrincipal(guest.id(),visit.id(),"test-hash");
        String key="parallel-" + UUID.randomUUID();
        var first=CompletableFuture.supplyAsync(()->checkout.checkout(principal,
            List.of(new CashCheckoutService.Line(menuItem,1)),key));
        var second=CompletableFuture.supplyAsync(()->checkout.checkout(principal,
            List.of(new CashCheckoutService.Line(menuItem,1)),key));
        assertEquals(first.join().id(),second.join().id());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM orders WHERE guest_id=?",
            Integer.class,guest.id()));
    }
}
