package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.regex.Pattern;
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

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class AuditReviewIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_audit").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", postgres::getJdbcUrl);
        props.add("spring.datasource.username", postgres::getUsername);
        props.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    private String login(String role) throws Exception {
        var id = UUID.randomUUID();
        var username = "audit-" + id;
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id, username, passwords.encode("synthetic-audit-password-2026"));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            id, role);
        var body = """
            {"login":"%s","password":"synthetic-audit-password-2026"}
            """.formatted(username);
        var response = mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var matcher = Pattern.compile("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"")
            .matcher(response.getContentAsString());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private void event(String operation, UUID resource, String secretReason) {
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,operation,resource_type,resource_id,
                                   correlation_id,reason,safe_before_after_diff)
            VALUES ('SYSTEM',?,'CAFE_TABLE',?,'synthetic-audit',?, '{"hidden":"secret-marker"}'::jsonb)
            """, operation, resource, secretReason);
    }

    @Test
    void auditSearchPaginatesAndNeverReturnsUnstructuredSensitiveColumns() throws Exception {
        var manager = login("MANAGER");
        UUID resource = UUID.randomUUID();
        for (int i=0; i<28; i++) event("TABLE_AUDIT_SEARCH", resource, "secret-marker-" + i);
        var result = mvc.perform(get("/api/v1/admin/audit-events?q=TABLE_AUDIT_SEARCH&page=0")
            .header("Authorization","Bearer " + manager))
            .andReturn().getResponse();
        assertEquals(200, result.getStatus(), result.getContentAsString());
        var json = result.getContentAsString();
        assertTrue(json.contains("\\"page\\":0"));
        assertTrue(json.contains("\\"pageSize\\":25"));
        assertTrue(json.contains("\\"hasNext\\":true"));
        assertTrue(json.contains("TABLE_AUDIT_SEARCH"));
        assertFalse(json.contains("secret-marker"));
        assertFalse(json.contains("safeBeforeAfterDiff"));
        assertFalse(json.contains("correlationId"));
        var second = mvc.perform(get("/api/v1/admin/audit-events?q=TABLE_AUDIT_SEARCH&page=1")
            .header("Authorization","Bearer " + manager))
            .andReturn().getResponse();
        assertEquals(200, second.getStatus(), second.getContentAsString());
        assertTrue(second.getContentAsString().contains("\\"hasNext\\":false"));
        assertEquals(400, mvc.perform(get("/api/v1/admin/audit-events?page=1001")
            .header("Authorization","Bearer " + manager))
            .andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/api/v1/admin/audit-events?q=bad%25")
            .header("Authorization","Bearer " + manager))
            .andReturn().getResponse().getStatus());
    }

    @Test
    void auditHistoryIsNotAvailableToUnauthenticatedOrOrdinaryStaff() throws Exception {
        assertEquals(401, mvc.perform(get("/api/v1/admin/audit-events"))
            .andReturn().getResponse().getStatus());
        for (String role : new String[] {"WAITER","KITCHEN","CASHIER"}) {
            var token = login(role);
            assertEquals(403, mvc.perform(get("/api/v1/admin/audit-events")
                .header("Authorization","Bearer " + token))
                .andReturn().getResponse().getStatus());
        }
    }
}
