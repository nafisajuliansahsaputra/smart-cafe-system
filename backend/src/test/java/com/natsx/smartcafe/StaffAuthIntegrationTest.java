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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class StaffAuthIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_auth_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;

    private record SyntheticStaff(UUID id, String login) {}
    private SyntheticStaff staff(String role) {
        UUID id = UUID.randomUUID();
        String login = "staff-" + id;
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id, login, passwords.encode("safe-test-password-123"));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)", id, role);
        return new SyntheticStaff(id, login);
    }

    private String loginJson(String username, String password) {
        return "{\"login\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private String token(String json) {
        var match = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(json);
        assertTrue(match.find(), "Login should return one opaque bearer token");
        return match.group(1);
    }

    @Test void loginRoleGatesAndLogoutRevokesSession() throws Exception {
        var cashier = staff("CASHIER");
        var ok = mvc.perform(post("/api/v1/staff/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(cashier.login(), "safe-test-password-123"))).andReturn().getResponse();
        assertEquals(200, ok.getStatus());
        assertTrue(ok.getHeader("Cache-Control").contains("no-store"));
        String bearer = token(ok.getContentAsString());
        assertEquals(43, bearer.length());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM staff_sessions WHERE token_hash=?",
            Integer.class, bearer));
        var me = mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization", "Bearer " + bearer)).andReturn().getResponse();
        assertEquals(200, me.getStatus());
        assertTrue(me.getContentAsString().contains("CASHIER"));
        assertFalse(me.getContentAsString().contains("safe-test-password"));
        assertEquals(403, mvc.perform(get("/api/v1/kitchen/tickets")
            .header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(post("/api/v1/staff/auth/logout")
            .header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus());
    }

    @Test void accountDisablementInvalidatesExistingBearer() throws Exception {
        var waiter = staff("WAITER");
        var response = mvc.perform(post("/api/v1/staff/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(waiter.login(), "safe-test-password-123"))).andReturn().getResponse();
        String bearer = token(response.getContentAsString());
        jdbc.update("UPDATE staff_users SET enabled=false WHERE id=?", waiter.id());
        assertEquals(401, mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus());
    }

    @Test void incorrectCredentialsCommitDatabaseThrottleAndNeverRevealAccount() throws Exception {
        var cashier = staff("CASHIER");
        for (int i=0; i<4; i++) {
            var fail = mvc.perform(post("/api/v1/staff/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(cashier.login(), "wrong-password-123"))).andReturn().getResponse();
            assertEquals(401, fail.getStatus());
            assertTrue(fail.getContentAsString().contains("UNAUTHENTICATED"));
        }
        var locked = mvc.perform(post("/api/v1/staff/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(cashier.login(), "wrong-password-123"))).andReturn().getResponse();
        assertEquals(429, locked.getStatus());
        assertEquals(5, jdbc.queryForObject("SELECT failed_attempts FROM staff_login_throttles WHERE login_hash=?",
            Integer.class, com.natsx.smartcafe.identity.SecureToken.sha256(cashier.login())));
        assertEquals(429, mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(cashier.login(), "safe-test-password-123"))).andReturn().getResponse().getStatus());
    }

    @Test void unauthenticatedAndUnknownBearerAreRejected() throws Exception {
        assertEquals(401, mvc.perform(get("/api/v1/staff/auth/me"))
            .andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization", "Bearer " + "a".repeat(43)))
            .andReturn().getResponse().getStatus());
    }
}
