package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class KitchenPaginationIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_kitchen_pages")
        .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;

    private record Staff(UUID id, String token) {}
    private Staff login(String role) throws Exception {
        UUID id = UUID.randomUUID();
        String username = "kds-page-" + id;
        String password = "synthetic-kitchen-pagination-password";
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id,username,passwords.encode(password));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",id,role);
        var response = mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("login",username,"password",password))))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        return new Staff(id,mapper.readTree(response.getContentAsString())
            .path("data").path("accessToken").asText());
    }

    private UUID[] seedVisit(Staff opener) {
        UUID table=UUID.randomUUID(),visit=UUID.randomUUID(),guest=UUID.randomUUID();
        String code="KDS-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,code,code.toLowerCase(Locale.ROOT),4);
        jdbc.update("INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash) VALUES (?,?,?,?)",
            visit,table,opener.id(),"e".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic KDS visitor");
        return new UUID[]{visit,guest};
    }

    private void makeTicket(UUID visit,UUID guest,UUID orderId,String state) {
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?, ?,12000,12000,'synthetic-kds-page')
            """,orderId,visit,guest,state);
        jdbc.update("""
            INSERT INTO kitchen_tickets(order_id,status)
            VALUES (?,?)
            """,orderId,state);
    }

    private JsonNode page(String token,int index) throws Exception {
        var response=mvc.perform(get("/api/v1/kitchen/tickets/page")
            .param("page",Integer.toString(index))
            .header("Authorization","Bearer "+token))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertFalse(response.getContentAsString().contains("joinProof"));
        assertFalse(response.getContentAsString().contains("guestId"));
        assertFalse(response.getContentAsString().contains("paymentIntentId"));
        return mapper.readTree(response.getContentAsString()).path("data");
    }

    @Test
    void kitchenCanReachAll112TicketsAndPreparingRemainsHighestPriority() throws Exception {
        Staff kitchen=login("KITCHEN");
        UUID[] visit=seedVisit(kitchen);
        jdbc.update("""
            INSERT INTO orders(visit_id,guest_id,status,subtotal_idr,total_idr,
                               pricing_policy_version,created_at)
            SELECT ?,?,'READY',12000,12000,'synthetic-kds-page',
                now()-n*interval '1 second'
            FROM generate_series(1,110) AS n
            """,visit[0],visit[1]);
        jdbc.update("""
            INSERT INTO kitchen_tickets(order_id,status)
            SELECT id,'READY' FROM orders WHERE visit_id=?
            """,visit[0]);
        UUID accepted=UUID.randomUUID(),preparing=UUID.randomUUID();
        makeTicket(visit[0],visit[1],accepted,"ACCEPTED");
        makeTicket(visit[0],visit[1],preparing,"PREPARING");
        var seen=new HashSet<String>();
        for(int n=0;n<5;n++) {
            JsonNode current=page(kitchen.token(),n);
            assertEquals(n,current.path("page").asInt());
            assertEquals(25,current.path("pageSize").asInt());
            assertEquals(112,current.path("total").asInt());
            assertEquals(n<4,current.path("hasNext").asBoolean());
            assertEquals(n==4?12:25,current.path("items").size());
            for(JsonNode row:current.path("items")) {
                assertTrue(seen.add(row.path("id").asText()),"duplicate ticket across pages");
                assertTrue(row.path("lines").isArray());
            }
        }
        assertEquals(112,seen.size());
        JsonNode top=page(kitchen.token(),0).path("items");
        assertEquals("PREPARING",top.get(0).path("status").asText());
        assertEquals(preparing.toString(),top.get(0).path("orderId").asText());
        assertEquals("ACCEPTED",top.get(1).path("status").asText());
        assertEquals(accepted.toString(),top.get(1).path("orderId").asText());
        assertEquals("READY",top.get(2).path("status").asText());
        assertEquals(0,page(kitchen.token(),5).path("items").size());
        assertFalse(page(kitchen.token(),5).path("hasNext").asBoolean());

        var legacy=mvc.perform(get("/api/v1/kitchen/tickets")
            .header("Authorization","Bearer "+kitchen.token()))
            .andReturn().getResponse();
        assertEquals(200,legacy.getStatus());
        assertEquals(100,mapper.readTree(legacy.getContentAsString()).path("data").size());

        for(String invalid:new String[]{"-1","1001","nonsense"}) {
            assertEquals(400,mvc.perform(get("/api/v1/kitchen/tickets/page")
                .param("page",invalid)
                .header("Authorization","Bearer "+kitchen.token()))
                .andReturn().getResponse().getStatus());
        }
    }

    @Test
    void onlyKitchenManagerAndAdminMayReadPagedTickets() throws Exception {
        assertEquals(401,mvc.perform(get("/api/v1/kitchen/tickets/page"))
            .andReturn().getResponse().getStatus());
        for(String role:new String[]{"WAITER","CASHIER"}) {
            Staff denied=login(role);
            assertEquals(403,mvc.perform(get("/api/v1/kitchen/tickets/page")
                .header("Authorization","Bearer "+denied.token()))
                .andReturn().getResponse().getStatus());
        }
        for(String role:new String[]{"KITCHEN","MANAGER","ADMIN"}) {
            Staff allowed=login(role);
            assertEquals(25,page(allowed.token(),0).path("pageSize").asInt());
        }
    }
}
