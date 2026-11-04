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
class WaiterTaskPaginationIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_waiter_task_pages")
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
        String user = "waiter-pager-" + id;
        String password = "synthetic-waiter-pagination-password";
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id, user, passwords.encode(password));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            id, role);
        var response = mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("login", user, "password", password))))
            .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return new Staff(id, mapper.readTree(response.getContentAsString())
            .path("data").path("accessToken").asText());
    }

    private UUID[] visit(Staff opener) {
        UUID table = UUID.randomUUID(), visit = UUID.randomUUID(), guest = UUID.randomUUID();
        String code = "WT-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table, code, code.toLowerCase(Locale.ROOT), 4);
        jdbc.update("INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash) VALUES (?,?,?,?)",
            visit, table, opener.id(), "e".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic Waiter Guest");
        return new UUID[] {visit, guest};
    }

    private void readyOrders(UUID visit, UUID guest, int count) {
        Integer n = jdbc.queryForObject("""
            WITH inserted AS (
              INSERT INTO orders(visit_id,guest_id,status,subtotal_idr,total_idr,
                                 pricing_policy_version,created_at)
              SELECT ?,?,'READY',18000,18000,'synthetic-ready',
                     now() - n * interval '1 second'
              FROM generate_series(1,?) AS n
              RETURNING id,total_idr
            ), intents AS (
              INSERT INTO payment_intents(order_id,method_selected,amount_due_idr,aggregate_status)
              SELECT id,'CASH',total_idr,'AWAITING_CASH' FROM inserted
              RETURNING id
            ), tickets AS (
              INSERT INTO kitchen_tickets(order_id,status)
              SELECT id,'READY' FROM inserted
              RETURNING id
            )
            SELECT count(*) FROM inserted
            """, Integer.class, visit, guest, count);
        assertEquals(count, n);
    }

    private UUID servedCashDue(UUID visit, UUID guest) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,
                               pricing_policy_version,created_at)
            VALUES (?,?,?,'SERVED',22000,22000,'synthetic-cash',
                    now() - interval '500 milliseconds')
            """, id, visit, guest);
        jdbc.update("""
            INSERT INTO payment_intents(order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,'CASH',22000,'AWAITING_CASH')
            """, id);
        return id;
    }

    private JsonNode page(String bearer, int number) throws Exception {
        var response = mvc.perform(get("/api/v1/waiter/orders/tasks/page")
            .param("page", Integer.toString(number))
            .header("Authorization", "Bearer " + bearer))
            .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertFalse(response.getContentAsString().contains("joinProof"));
        assertFalse(response.getContentAsString().contains("guestId"));
        assertFalse(response.getContentAsString().contains("password"));
        return mapper.readTree(response.getContentAsString()).path("data");
    }

    @Test
    void unpaidCashStaysFirstAndAllOlderTasksReachableAcrossMoreThan100Rows() throws Exception {
        Staff waiter = login("WAITER"), cashier = login("CASHIER");
        UUID[] visit = visit(waiter);
        readyOrders(visit[0], visit[1], 110);
        UUID cashDue = servedCashDue(visit[0], visit[1]);

        var seen = new HashSet<String>();
        for (int n = 0; n < 5; n++) {
            JsonNode current = page(waiter.token(), n);
            assertEquals(n, current.path("page").asInt());
            assertEquals(25, current.path("pageSize").asInt());
            assertEquals(111, current.path("total").asInt());
            assertEquals(n < 4, current.path("hasNext").asBoolean());
            assertEquals(n == 4 ? 11 : 25, current.path("items").size());
            for (JsonNode row : current.path("items")) {
                assertTrue(seen.add(row.path("orderId").asText()));
                assertEquals("AWAITING_CASH", row.path("paymentStatus").asText());
            }
        }
        assertEquals(111, seen.size());

        JsonNode top = page(waiter.token(), 0).path("items").get(0);
        assertEquals(cashDue.toString(), top.path("orderId").asText());
        assertEquals("SERVED", top.path("orderStatus").asText());
        assertEquals(22000, top.path("totalIdr").asInt());
        JsonNode finalPage = page(waiter.token(), 5);
        assertEquals(0, finalPage.path("items").size());
        assertFalse(finalPage.path("hasNext").asBoolean());

        // Supervisor sees the entire scoped population, but the legacy API
        // keeps its old 100-row contract for backward compatibility.
        assertEquals(111, page(cashier.token(), 0).path("total").asInt());
        var legacy = mvc.perform(get("/api/v1/waiter/orders/tasks")
            .header("Authorization", "Bearer " + waiter.token()))
            .andReturn().getResponse();
        assertEquals(200, legacy.getStatus());
        assertEquals(100, mapper.readTree(legacy.getContentAsString()).path("data").size());

        for (String invalid : new String[]{"-1", "1001", "not-an-integer"}) {
            assertEquals(400, mvc.perform(get("/api/v1/waiter/orders/tasks/page")
                .param("page", invalid)
                .header("Authorization", "Bearer " + waiter.token()))
                .andReturn().getResponse().getStatus());
        }
    }

    @Test
    void waiterOwnershipAndKitchenDenyAreEnforcedBeforePagination() throws Exception {
        Staff waiter = login("WAITER"), otherWaiter = login("WAITER");
        Staff cashier = login("CASHIER"), manager = login("MANAGER");
        Staff kitchen = login("KITCHEN"), admin = login("ADMIN");
        // Both test methods share this class's Testcontainers database. Compare
        // supervisors against the initial count instead of assuming no fixtures
        // from the earlier 111-row pagination scenario are present.
        int existingTasks = page(cashier.token(), 0).path("total").asInt();
        UUID[] visit = visit(waiter);
        readyOrders(visit[0], visit[1], 2);
        assertEquals(2, page(waiter.token(), 0).path("total").asInt());
        assertEquals(0, page(otherWaiter.token(), 0).path("total").asInt());
        for (Staff supervisor : new Staff[] {cashier,manager,admin}) {
            assertEquals(existingTasks + 2, page(supervisor.token(), 0).path("total").asInt());
        }
        assertEquals(403, mvc.perform(get("/api/v1/waiter/orders/tasks/page")
            .header("Authorization", "Bearer " + kitchen.token()))
            .andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/v1/waiter/orders/tasks/page"))
            .andReturn().getResponse().getStatus());
    }
}
