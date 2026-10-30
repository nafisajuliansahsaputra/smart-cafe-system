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
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
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
    @Autowired com.natsx.smartcafe.orders.CashWorkflowService workflow;
    @Autowired com.natsx.smartcafe.common.events.OutboxLiveRelay liveRelay;

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
        var ownedDetail=mvc.perform(get("/api/v1/me/orders/"+orderId)
            .header("Authorization","Bearer "+a.token())).andReturn().getResponse();
        assertEquals(200,ownedDetail.getStatus());
        assertTrue(ownedDetail.getContentAsString().contains("Synthetic Meal"));
        assertTrue(ownedDetail.getContentAsString().contains("AWAITING_CASH"));
        assertEquals(404,mvc.perform(get("/api/v1/me/orders/"+orderId)
            .header("Authorization","Bearer "+b.token()))
            .andReturn().getResponse().getStatus());
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
            "INSERT INTO receipts(payment_intent_id,order_id,total_idr,document_json) " +
            "VALUES ((SELECT id FROM payment_intents WHERE order_id=?),?,?,'{}'::jsonb)",
            orderId,orderId,56000));
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
        var unassignedWaiter=staff("WAITER");
        var ownerTasks=mvc.perform(get("/api/v1/waiter/orders/tasks")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse();
        assertEquals(200,ownerTasks.getStatus());
        assertTrue(ownerTasks.getContentAsString().contains(orderId.toString()));
        assertTrue(ownerTasks.getContentAsString().contains(intent.toString()));
        assertTrue(ownerTasks.getContentAsString().contains("READY"));
        assertTrue(!ownerTasks.getContentAsString().contains("accessToken"));
        var otherTasks=mvc.perform(get("/api/v1/waiter/orders/tasks")
            .header("Authorization","Bearer " + unassignedWaiter)).andReturn().getResponse();
        assertEquals(200,otherTasks.getStatus());
        assertTrue(!otherTasks.getContentAsString().contains(orderId.toString()));
        var cashierTasks=mvc.perform(get("/api/v1/waiter/orders/tasks")
            .header("Authorization","Bearer " + cashier)).andReturn().getResponse();
        assertEquals(200,cashierTasks.getStatus());
        assertTrue(cashierTasks.getContentAsString().contains(orderId.toString()));
        assertEquals(403,mvc.perform(get("/api/v1/waiter/orders/tasks")
            .header("Authorization","Bearer " + kitchen)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/waiter/orders/tasks"))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/api/v1/waiter/orders/" + orderId + "/served")
            .header("Authorization","Bearer " + unassignedWaiter)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/waiter/orders/" + orderId + "/served")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse().getStatus());
        assertEquals("AWAITING_CASH",jdbc.queryForObject(
            "SELECT aggregate_status FROM payment_intents WHERE id=?",String.class,intent));

        assertEquals(404,mvc.perform(get("/api/v1/me/orders/" + orderId + "/receipt")
            .header("Authorization","Bearer " + a.token())).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/v1/me/orders/" + orderId + "/receipt")
            .header("Authorization","Bearer " + b.token())).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/me/orders/" + orderId + "/receipt"))
            .andReturn().getResponse().getStatus());

        String tenderKey="tender-" + UUID.randomUUID();
        assertEquals(403,mvc.perform(post("/api/v1/ops/payments/" + intent + "/cash-tenders")
            .header("Authorization","Bearer " + unassignedWaiter)
            .header("Idempotency-Key","unassigned-tender")
            .contentType(MediaType.APPLICATION_JSON).content("{\"amountReceivedIdr\":60000}"))
            .andReturn().getResponse().getStatus());
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
        var settledTasks=mvc.perform(get("/api/v1/waiter/orders/tasks")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse();
        assertEquals(200,settledTasks.getStatus());
        assertTrue(settledTasks.getContentAsString().contains("\"receivedIdr\":60000"));
        assertTrue(settledTasks.getContentAsString().contains("\"changeGivenIdr\":4000"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cash_tenders WHERE payment_intent_id=?",
            Integer.class,intent));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM receipts WHERE order_id=?",
            Integer.class,orderId));

        // M5 daily cash reporting: finalized receipts, never pending orders.
        var reportDay=jdbc.queryForObject("""
            SELECT (issued_at AT TIME ZONE 'Asia/Jakarta')::date
            FROM receipts WHERE order_id=?
            """,java.sql.Date.class,orderId).toLocalDate().toString();
        var report=mvc.perform(get("/api/v1/ops/reports/cash-daily")
            .param("date",reportDay)
            .header("Authorization","Bearer "+cashier)).andReturn().getResponse();
        assertEquals(200,report.getStatus(),report.getContentAsString());
        assertTrue(report.getHeader("Cache-Control").contains("no-store"));
        assertTrue(report.getContentAsString().contains("\"timezone\":\"Asia/Jakarta\""));
        assertTrue(report.getContentAsString().contains("\"currency\":\"IDR\""));
        assertTrue(report.getContentAsString().contains("\"name\":\"Synthetic Meal\""));
        var expectedGross=jdbc.queryForObject("""
            SELECT COALESCE(SUM(r.total_idr),0) FROM receipts r
            JOIN payment_intents p ON p.id=r.payment_intent_id
            WHERE p.method_selected='CASH'
              AND (r.issued_at AT TIME ZONE 'Asia/Jakarta')::date=CAST(? AS date)
            """,Long.class,reportDay);
        assertTrue(report.getContentAsString().contains(
            "\"grossCollectedIdr\":"+expectedGross));
        assertEquals(401,mvc.perform(get("/api/v1/ops/reports/cash-daily"))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/v1/ops/reports/cash-daily")
            .header("Authorization","Bearer "+waiter)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/v1/ops/reports/cash-daily")
            .header("Authorization","Bearer "+kitchen)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/v1/ops/reports/cash-daily")
            .header("Authorization","Bearer "+a.token())).andReturn().getResponse().getStatus());
        for(String invalid : List.of("2026-02-30", "2026/10/31", "1800-01-01", "x' OR 1=1 --")) {
            assertEquals(400,mvc.perform(get("/api/v1/ops/reports/cash-daily")
                .param("date",invalid).header("Authorization","Bearer "+cashier))
                .andReturn().getResponse().getStatus());
        }
        var emptyReport=mvc.perform(get("/api/v1/ops/reports/cash-daily")
            .param("date","2000-01-01")
            .header("Authorization","Bearer "+cashier)).andReturn().getResponse();
        assertEquals(200,emptyReport.getStatus());
        assertTrue(emptyReport.getContentAsString().contains("\"paidOrders\":0"));
        assertTrue(emptyReport.getContentAsString().contains("\"grossCollectedIdr\":0"));
        assertEquals("PAID",jdbc.queryForObject(
            "SELECT aggregate_status FROM payment_intents WHERE id=?",String.class,intent));
        assertEquals(200,mvc.perform(get("/api/v1/me/receipts/" + receipt)
            .header("Authorization","Bearer " + a.token())).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/v1/me/receipts/" + receipt)
            .header("Authorization","Bearer " + b.token())).andReturn().getResponse().getStatus());
        var ownReceiptByOrder=mvc.perform(get("/api/v1/me/orders/" + orderId + "/receipt")
            .header("Authorization","Bearer " + a.token())).andReturn().getResponse();
        assertEquals(200,ownReceiptByOrder.getStatus());
        assertTrue(ownReceiptByOrder.getHeader("Cache-Control").contains("no-store"));
        assertTrue(ownReceiptByOrder.getContentAsString().contains(receipt.toString()));
        assertEquals(404,mvc.perform(get("/api/v1/me/orders/" + orderId + "/receipt")
            .header("Authorization","Bearer " + b.token())).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/me/logout")
            .header("Authorization","Bearer " + a.token())).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/me/visit")
            .header("Authorization","Bearer " + a.token())).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/me/orders/" + orderId + "/receipt")
            .header("Authorization","Bearer " + a.token())).andReturn().getResponse().getStatus());
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

    @Test void simultaneousCashTendersNeverDoubleSettle() throws Exception {
        String waiter=staff("WAITER"),cashier=staff("CASHIER"),kitchen=staff("KITCHEN");
        var visit=open(waiter);
        var guest=join(visit,"Tender Race Guest");
        UUID menuItem=item(12000);
        var response=submit(guest,menuItem,1,"race-"+UUID.randomUUID());
        assertEquals(200,response.getStatus());
        UUID orderId=UUID.fromString(value(response.getContentAsString(),"id"));
        assertEquals(200,mvc.perform(post("/api/v1/ops/orders/"+orderId+"/approve-cash")
            .header("Authorization","Bearer "+cashier)).andReturn().getResponse().getStatus());
        UUID ticket=jdbc.queryForObject("SELECT id FROM kitchen_tickets WHERE order_id=?",
            UUID.class,orderId);
        UUID intent=jdbc.queryForObject("SELECT id FROM payment_intents WHERE order_id=?",
            UUID.class,orderId);
        assertEquals(200,mvc.perform(post("/api/v1/kitchen/tickets/"+ticket+"/start")
            .header("Authorization","Bearer "+kitchen)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/kitchen/tickets/"+ticket+"/ready")
            .header("Authorization","Bearer "+kitchen)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/waiter/orders/"+orderId+"/served")
            .header("Authorization","Bearer "+waiter)).andReturn().getResponse().getStatus());
        UUID staffId=jdbc.queryForObject(
            "SELECT staff_user_id FROM staff_sessions WHERE token_hash=?",UUID.class,
            com.natsx.smartcafe.identity.SecureToken.sha256(waiter));
        var ready=new java.util.concurrent.CountDownLatch(2);
        var go=new java.util.concurrent.CountDownLatch(1);
        java.util.function.Supplier<Boolean> first=()->{
            ready.countDown();
            try {
                go.await();
                workflow.capture(intent,15000,"cash-race-1",staffId,"race-request-1");
                return true;
            } catch(com.natsx.smartcafe.common.api.ApiFailureException ex) {
                return false;
            } catch(InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        };
        java.util.function.Supplier<Boolean> second=()->{
            ready.countDown();
            try {
                go.await();
                workflow.capture(intent,15000,"cash-race-2",staffId,"race-request-2");
                return true;
            } catch(com.natsx.smartcafe.common.api.ApiFailureException ex) {
                return false;
            } catch(InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        };
        var a=CompletableFuture.supplyAsync(first);
        var b=CompletableFuture.supplyAsync(second);
        assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
        go.countDown();
        int successful=(a.join()?1:0)+(b.join()?1:0);
        assertEquals(1,successful);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cash_tenders WHERE payment_intent_id=?",
            Integer.class,intent));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM receipts WHERE order_id=?",
            Integer.class,orderId));
        assertEquals("PAID",jdbc.queryForObject("SELECT aggregate_status FROM payment_intents WHERE id=?",
            String.class,intent));
    }

    @Test void rejectingUnpaidOrderReleasesAllocatedStockExactlyOnce() throws Exception {
        String waiter=staff("WAITER"),cashier=staff("CASHIER");
        var visit=open(waiter);
        var customer=join(visit,"Rejected Cash Guest");
        UUID item=item(12000);
        jdbc.update("UPDATE item_availability SET limited_quantity=1 WHERE menu_item_id=?",item);
        var first=submit(customer,item,1,"rejected-"+UUID.randomUUID());
        assertEquals(200,first.getStatus(),first.getContentAsString());
        UUID orderId=UUID.fromString(value(first.getContentAsString(),"id"));
        assertEquals(0L,jdbc.queryForObject(
            "SELECT limited_quantity FROM item_availability WHERE menu_item_id=?",Long.class,item));
        assertEquals(409,submit(customer,item,1,"soldout-"+UUID.randomUUID()).getStatus());

        String reason="{\"reason\":\"Wrong table request, rejected before cooking\"}";
        assertEquals(403,mvc.perform(post("/api/v1/ops/orders/"+orderId+"/reject")
            .header("Authorization","Bearer "+waiter)
            .contentType(MediaType.APPLICATION_JSON).content(reason))
            .andReturn().getResponse().getStatus());
        var rejected=mvc.perform(post("/api/v1/ops/orders/"+orderId+"/reject")
            .header("Authorization","Bearer "+cashier)
            .contentType(MediaType.APPLICATION_JSON).content(reason)).andReturn().getResponse();
        assertEquals(200,rejected.getStatus(),rejected.getContentAsString());
        assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",
            String.class,orderId));
        assertEquals("UNPAID",jdbc.queryForObject(
            "SELECT aggregate_status FROM payment_intents WHERE order_id=?",String.class,orderId));
        assertEquals(1L,jdbc.queryForObject(
            "SELECT limited_quantity FROM item_availability WHERE menu_item_id=?",Long.class,item));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM order_stock_allocations WHERE order_id=? AND released_at IS NOT NULL
            """,Integer.class,orderId));
        assertEquals(409,mvc.perform(post("/api/v1/ops/orders/"+orderId+"/reject")
            .header("Authorization","Bearer "+cashier)
            .contentType(MediaType.APPLICATION_JSON).content(reason))
            .andReturn().getResponse().getStatus());
        assertEquals(1L,jdbc.queryForObject(
            "SELECT limited_quantity FROM item_availability WHERE menu_item_id=?",Long.class,item));
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM receipts WHERE order_id=?",Integer.class,orderId));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs WHERE resource_id=? AND operation='CASH_ORDER_REJECTED'
            """,Integer.class,orderId));
        assertEquals(200,mvc.perform(post("/api/v1/waiter/table-visits/"+visit.id()+"/close")
            .header("Authorization","Bearer "+waiter))
            .andReturn().getResponse().getStatus());
    }

    private long liveCursor(String json) {
        var matcher=Pattern.compile("\"cursor\":(\\d+)").matcher(json);
        assertTrue(matcher.find(),"Missing cursor in response");
        return Long.parseLong(matcher.group(1));
    }

    private org.springframework.mock.web.MockHttpServletResponse live(
        String url, String token, String topic, UUID order, Long after) throws Exception {
        var request=get(url).param("topic",topic)
            .header("Authorization","Bearer "+token);
        if(order!=null) request.param("orderId",order.toString());
        if(after!=null) request.param("after",String.valueOf(after));
        var initial = mvc.perform(request).andReturn();
        if (after != null && initial.getRequest().isAsyncStarted()) {
            // After-cursor requests now use Servlet asynchronous dispatch.
            assertTrue(initial.getRequest().isAsyncStarted());
            return mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(initial)).andReturn().getResponse();
        }
        return initial.getResponse();
    }

    @Test void durableLiveCursorIsScopedAndReconnectCanCatchUp() throws Exception {
        var waiter=staff("WAITER");
        var unassigned=staff("WAITER");
        var cashier=staff("CASHIER");
        var kitchen=staff("KITCHEN");
        var visit=open(waiter);
        var owner=join(visit,"Live owner");
        var other=join(visit,"Live other");
        var meal=item(12000);
        var orderResponse=submit(owner,meal,1,"live-"+UUID.randomUUID());
        assertEquals(200,orderResponse.getStatus(),orderResponse.getContentAsString());
        UUID order=UUID.fromString(value(orderResponse.getContentAsString(),"id"));

        // Domain event exists, but remains pending until the separate relay runs.
        assertTrue(jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events
            WHERE aggregate_id=? AND live_published_at IS NULL
            """,Integer.class,order)>0);
        assertTrue(liveRelay.dispatchBatch()>0);
        var ownerOrder=live("/api/v1/me/live-cursor",owner.token(),"order",order,null);
        assertEquals(200,ownerOrder.getStatus());
        long first=liveCursor(ownerOrder.getContentAsString());
        assertTrue(first>0);

        assertEquals(404,live("/api/v1/me/live-cursor",other.token(),"order",order,null).getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/me/live-cursor").param("topic","orders"))
            .andReturn().getResponse().getStatus());
        assertEquals(403,live("/api/v1/ops/live-cursor",waiter,"orders",null,null).getStatus());
        assertEquals(403,live("/api/v1/kitchen/live-cursor",cashier,"kitchen",null,null).getStatus());
        assertEquals(400,live("/api/v1/me/live-cursor",owner.token(),"order",null,null).getStatus());
        assertEquals(400,live("/api/v1/me/live-cursor",owner.token(),"order",order,-1L).getStatus());

        long strangerCursor=liveCursor(live("/api/v1/me/live-cursor",
            other.token(),"orders",null,null).getContentAsString());
        long waiterBefore=liveCursor(live("/api/v1/waiter/live-cursor",
            waiter,"waiter",null,null).getContentAsString());
        long otherWaiterBefore=liveCursor(live("/api/v1/waiter/live-cursor",
            unassigned,"waiter",null,null).getContentAsString());
        long kitchenBefore=liveCursor(live("/api/v1/kitchen/live-cursor",
            kitchen,"kitchen",null,null).getContentAsString());

        assertEquals(200,mvc.perform(post("/api/v1/ops/orders/"+order+"/approve-cash")
            .header("Authorization","Bearer "+cashier))
            .andReturn().getResponse().getStatus());
        liveRelay.dispatchBatch();
        long kitchenAfter=liveCursor(live("/api/v1/kitchen/live-cursor",
            kitchen,"kitchen",null,null).getContentAsString());
        assertTrue(kitchenAfter>kitchenBefore);
        long ownerLater=liveCursor(live("/api/v1/me/live-cursor",
            owner.token(),"order",order,first).getContentAsString());
        assertTrue(ownerLater>first);
        assertEquals(strangerCursor,liveCursor(live("/api/v1/me/live-cursor",
            other.token(),"orders",null,null).getContentAsString()));

        UUID ticket=jdbc.queryForObject(
            "SELECT id FROM kitchen_tickets WHERE order_id=?",UUID.class,order);
        assertEquals(200,mvc.perform(post("/api/v1/kitchen/tickets/"+ticket+"/start")
            .header("Authorization","Bearer "+kitchen)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/kitchen/tickets/"+ticket+"/ready")
            .header("Authorization","Bearer "+kitchen)).andReturn().getResponse().getStatus());
        liveRelay.dispatchBatch();
        long waiterAfter=liveCursor(live("/api/v1/waiter/live-cursor",
            waiter,"waiter",null,null).getContentAsString());
        assertTrue(waiterAfter>waiterBefore);
        assertEquals(otherWaiterBefore,liveCursor(live("/api/v1/waiter/live-cursor",
            unassigned,"waiter",null,null).getContentAsString()));
    }


    @Test void longPollReleasesServletThreadAndReturnsAuthorizedCursor() throws Exception {
        String cashier=staff("CASHIER");
        var start=mvc.perform(get("/api/v1/ops/live-cursor")
            .param("topic","orders").header("Authorization","Bearer "+cashier))
            .andReturn().getResponse();
        long after=liveCursor(start.getContentAsString());

        // The request thread should release immediately even if no new event
        // is published. The waiting happens on the virtual-thread executor.
        long began=System.nanoTime();
        var pending=mvc.perform(get("/api/v1/ops/live-cursor")
            .param("topic","orders").param("after",Long.toString(after))
            .header("Authorization","Bearer "+cashier)).andReturn();
        assertTrue(pending.getRequest().isAsyncStarted(),
            "After-cursor must use asynchronous MVC return handling");
        assertTrue((System.nanoTime()-began)/1_000_000 < 5000,
            "Servlet dispatch must not block for the 14-second cursor wait");

        var sampled=mvc.perform(get("/api/v1/ops/live-cursor")
            .param("topic","orders").header("Authorization","Bearer "+cashier))
            .andReturn().getResponse();
        assertEquals(200,sampled.getStatus(),sampled.getContentAsString());

        // The outstanding request eventually completes with the current
        // position. Do not require a notification to finish this test.
        var resolved=mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .asyncDispatch(pending)).andReturn().getResponse();
        assertEquals(200,resolved.getStatus(),resolved.getContentAsString());
        assertEquals(after,liveCursor(resolved.getContentAsString()));
    }

}
