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
class OccOrderPaginationIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_occ_pages")
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
        String username = "occ-page-" + id;
        String password = "synthetic-occ-pagination-secret";
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id, username, passwords.encode(password));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)", id, role);
        var response = mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("login", username, "password", password))))
            .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return new Staff(id, mapper.readTree(response.getContentAsString())
            .path("data").path("accessToken").asText());
    }

    private String insertSyntheticOrders(Staff waiter, int count) {
        String code = "OCC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID table = UUID.randomUUID(), visit = UUID.randomUUID(), guest = UUID.randomUUID();
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table, code, code.toLowerCase(Locale.ROOT), 4);
        jdbc.update("INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash) VALUES (?,?,?,?)",
            visit, table, waiter.id(), "e".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Synthetic OCC Visitor");
        jdbc.update("""
            WITH inserted AS (
                INSERT INTO orders(visit_id,guest_id,status,subtotal_idr,total_idr,
                                   pricing_policy_version,created_at)
                SELECT ?,?,'SUBMITTED',12000,12000,'synthetic-test',
                       now() - n * interval '1 second'
                FROM generate_series(1,?) AS n
                RETURNING id,total_idr
            )
            INSERT INTO payment_intents(order_id,method_selected,amount_due_idr,aggregate_status)
            SELECT id,'CASH',total_idr,'AWAITING_CASH' FROM inserted
            """, visit, guest, count);
        return code;
    }

    private JsonNode page(String token, int number, String tableCode) throws Exception {
        var response = mvc.perform(get("/api/v1/ops/orders/page")
            .param("page", Integer.toString(number)).param("tableCode", tableCode)
            .header("Authorization", "Bearer " + token))
            .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertFalse(response.getContentAsString().contains("joinProof"));
        assertFalse(response.getContentAsString().contains("guestId"));
        return mapper.readTree(response.getContentAsString()).path("data");
    }

    @Test
    void cashierCanReachOrdersBeyondOld100CapWithStableFiltersAndNoSecretData() throws Exception {
        Staff cashier = login("CASHIER");
        Staff waiter = login("WAITER");
        String tableCode = insertSyntheticOrders(waiter, 108);
        var seen = new HashSet<String>();
        for (int n = 0; n < 5; n++) {
            JsonNode current = page(cashier.token(), n, tableCode);
            assertEquals(n, current.path("page").asInt());
            assertEquals(25, current.path("pageSize").asInt());
            assertEquals(108, current.path("total").asInt());
            assertEquals(n < 4, current.path("hasNext").asBoolean());
            assertEquals(n == 4 ? 8 : 25, current.path("items").size());
            for (JsonNode row : current.path("items")) {
                assertEquals(tableCode, row.path("tableCode").asText());
                assertEquals("SUBMITTED", row.path("orderStatus").asText());
                assertEquals("AWAITING_CASH", row.path("paymentStatus").asText());
                assertTrue(seen.add(row.path("id").asText()));
            }
        }
        assertEquals(108, seen.size());
        JsonNode afterLast = page(cashier.token(), 5, tableCode);
        assertEquals(0, afterLast.path("items").size());
        assertFalse(afterLast.path("hasNext").asBoolean());

        var old = mvc.perform(get("/api/v1/ops/orders")
            .param("tableCode", tableCode)
            .header("Authorization", "Bearer " + cashier.token()))
            .andReturn().getResponse();
        assertEquals(100, mapper.readTree(old.getContentAsString()).path("data").size());

        for (var filter : new String[] {"status=REJECTED", "paymentStatus=PAID",
            "tableCode=not-this-table"}) {
            var response = mvc.perform(get("/api/v1/ops/orders/page?" + filter)
                .header("Authorization", "Bearer " + cashier.token()))
                .andReturn().getResponse();
            assertEquals(200, response.getStatus());
            assertEquals(0, mapper.readTree(response.getContentAsString())
                .path("data").path("items").size());
        }
        for (var invalid : new String[] {"page=-1", "page=1001",
            "status=INVALID", "paymentStatus=INVALID",
            "tableCode=" + "X".repeat(41)}) {
            assertEquals(400, mvc.perform(get("/api/v1/ops/orders/page?" + invalid)
                .header("Authorization", "Bearer " + cashier.token()))
                .andReturn().getResponse().getStatus(), invalid);
        }
    }

    @Test
    void onlyCashierManagerAndAdminMaySeePaginatedOrderQueue() throws Exception {
        assertEquals(401, mvc.perform(get("/api/v1/ops/orders/page"))
            .andReturn().getResponse().getStatus());
        for (String role : new String[] {"KITCHEN", "WAITER"}) {
            Staff staff = login(role);
            assertEquals(403, mvc.perform(get("/api/v1/ops/orders/page")
                .header("Authorization", "Bearer " + staff.token()))
                .andReturn().getResponse().getStatus());
        }
        for (String role : new String[] {"CASHIER", "MANAGER", "ADMIN"}) {
            Staff staff = login(role);
            assertEquals(25, page(staff.token(), 0, "").path("pageSize").asInt());
        }
    }
}
