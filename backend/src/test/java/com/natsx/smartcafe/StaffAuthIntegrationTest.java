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
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
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


    @Test void managerStaffDirectoryAndRevocableStatusChanges() throws Exception {
        var manager=staff("MANAGER");
        var admin=staff("ADMIN");
        var cashier=staff("CASHIER");
        var waiter=staff("WAITER");
        String managerToken=loginToken(manager);
        String adminToken=loginToken(admin);
        String cashierToken=loginToken(cashier);
        String waiterToken=loginToken(waiter);
        var directory=mvc.perform(get("/api/v1/admin/staff?page=0")
            .header("Authorization","Bearer "+managerToken)).andReturn().getResponse();
        assertEquals(200,directory.getStatus());
        assertTrue(directory.getHeader("Cache-Control").contains("no-store"));
        assertTrue(directory.getContentAsString().contains(waiter.login()));
        assertFalse(directory.getContentAsString().contains("password_hash"));
        assertFalse(directory.getContentAsString().contains("token_hash"));
        assertFalse(directory.getContentAsString().contains("safe-test-password"));
        assertEquals(401,mvc.perform(get("/api/v1/admin/staff"))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/v1/admin/staff")
            .header("Authorization","Bearer "+cashierToken))
            .andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/api/v1/admin/staff?page=2001")
            .header("Authorization","Bearer "+managerToken))
            .andReturn().getResponse().getStatus());

        String disable="{\"enabled\":false,\"expectedEnabled\":true,\"reason\":\"End of shift\"}";
        String enable="{\"enabled\":true,\"expectedEnabled\":false,\"reason\":\"Access reapproved\"}";
        String path="/api/v1/admin/staff/"+waiter.id()+"/status";
        assertEquals(403,mvc.perform(post(path)
            .header("Authorization","Bearer "+cashierToken)
            .contentType(MediaType.APPLICATION_JSON).content(disable))
            .andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(post(path)
            .header("Authorization","Bearer "+managerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false,\"expectedEnabled\":true,\"reason\":\"No\"}"))
            .andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post(path)
            .header("Authorization","Bearer "+managerToken)
            .contentType(MediaType.APPLICATION_JSON).content(disable))
            .andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization","Bearer "+waiterToken)).andReturn().getResponse().getStatus());
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM staff_sessions WHERE staff_user_id=? AND revoked_at IS NOT NULL
            """,Integer.class,waiter.id()));
        assertEquals(409,mvc.perform(post(path)
            .header("Authorization","Bearer "+managerToken)
            .contentType(MediaType.APPLICATION_JSON).content(disable))
            .andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post(path)
            .header("Authorization","Bearer "+adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(enable))
            .andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization","Bearer "+waiterToken)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(waiter.login(),"safe-test-password-123")))
            .andReturn().getResponse().getStatus());
        for(var privileged : new SyntheticStaff[]{admin,manager}) {
            assertEquals(403,mvc.perform(post("/api/v1/admin/staff/"+privileged.id()+"/status")
                .header("Authorization","Bearer "+adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(disable))
                .andReturn().getResponse().getStatus());
        }
        assertEquals(404,mvc.perform(post("/api/v1/admin/staff/"+UUID.randomUUID()+"/status")
            .header("Authorization","Bearer "+managerToken)
            .contentType(MediaType.APPLICATION_JSON).content(disable))
            .andReturn().getResponse().getStatus());
        assertEquals(2,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs WHERE resource_type='STAFF_USER' AND resource_id=?
            """,Integer.class,waiter.id()));
    }

    private String loginToken(SyntheticStaff person) throws Exception {
        var r=mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(person.login(),"safe-test-password-123")))
            .andReturn().getResponse();
        assertEquals(200,r.getStatus(),r.getContentAsString());
        return token(r.getContentAsString());
    }


    @Test void onlyAdminCanProvisionDisabledOrdinaryStaff() throws Exception {
        var admin=staff("ADMIN");
        var manager=staff("MANAGER");
        var cashier=staff("CASHIER");
        String adminBearer=loginToken(admin),managerBearer=loginToken(manager),
            cashierBearer=loginToken(cashier);
        String createdLogin="newstaff-"+UUID.randomUUID().toString().substring(0,8);
        String secret="synthetic-Provision-Password-2026";
        String payload="{\"login\":\""+createdLogin+"\",\"password\":\""+secret+
            "\",\"role\":\"WAITER\",\"reason\":\"New member approved\"}";
        assertEquals(401,mvc.perform(post("/api/v1/admin/staff")
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/api/v1/admin/staff")
            .header("Authorization","Bearer "+managerBearer)
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/api/v1/admin/staff")
            .header("Authorization","Bearer "+cashierBearer)
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andReturn().getResponse().getStatus());
        for(String deniedRole: java.util.List.of("ADMIN","MANAGER","SUPERADMIN","")) {
            String forged=payload.replace("\"WAITER\"","\""+deniedRole+"\"");
            assertEquals(400,mvc.perform(post("/api/v1/admin/staff")
                .header("Authorization","Bearer "+adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content(forged))
                .andReturn().getResponse().getStatus());
        }
        String weak=payload.replace(secret,"short");
        assertEquals(400,mvc.perform(post("/api/v1/admin/staff")
            .header("Authorization","Bearer "+adminBearer)
            .contentType(MediaType.APPLICATION_JSON).content(weak))
            .andReturn().getResponse().getStatus());
        var created=mvc.perform(post("/api/v1/admin/staff")
            .header("Authorization","Bearer "+adminBearer)
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andReturn().getResponse();
        assertEquals(201,created.getStatus(),created.getContentAsString());
        assertTrue(created.getHeader("Cache-Control").contains("no-store"));
        assertTrue(created.getContentAsString().contains("\"enabled\":false"));
        assertTrue(created.getContentAsString().contains("\"role\":\"WAITER\""));
        assertFalse(created.getContentAsString().contains(secret));
        assertFalse(created.getContentAsString().contains("password_hash"));
        var id=jdbc.queryForObject("SELECT id FROM staff_users WHERE login=?",
            UUID.class,createdLogin);
        assertTrue(passwords.matches(secret,jdbc.queryForObject(
            "SELECT password_hash FROM staff_users WHERE id=?",String.class,id)));
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM staff_role_assignments WHERE staff_user_id=? AND role_key='WAITER'",
            Integer.class,id));
        assertEquals(401,mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(createdLogin,secret)))
            .andReturn().getResponse().getStatus());
        assertEquals(409,mvc.perform(post("/api/v1/admin/staff")
            .header("Authorization","Bearer "+adminBearer)
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andReturn().getResponse().getStatus());
        assertEquals(409,mvc.perform(post("/api/v1/admin/staff")
            .header("Authorization","Bearer "+adminBearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload.replace(createdLogin,createdLogin.toUpperCase())))
            .andReturn().getResponse().getStatus());
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs
            WHERE resource_type='STAFF_USER' AND resource_id=? AND operation='STAFF_PROVISIONED'
            """,Integer.class,id));
        assertEquals(200,mvc.perform(post("/api/v1/admin/staff/"+id+"/status")
            .header("Authorization","Bearer "+adminBearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":true,\"expectedEnabled\":false,\"reason\":\"Ready for work\"}"))
            .andReturn().getResponse().getStatus());
        var login=mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(createdLogin,secret))).andReturn().getResponse();
        assertEquals(200,login.getStatus());
        var me=mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization","Bearer "+token(login.getContentAsString())))
            .andReturn().getResponse();
        assertEquals(200,me.getStatus());
        assertTrue(me.getContentAsString().contains("WAITER"));
        assertFalse(me.getContentAsString().contains("ADMIN"));
    }


    @Test void selfServiceRotationRevokesAllSessionsAndAuditsWithoutSecrets() throws Exception {
        var waiter=staff("WAITER");
        String first=loginToken(waiter);
        String second=loginToken(waiter);
        String changed="synthetic-rotated-Password-2026";
        String path="/api/v1/staff/auth/password/change";
        String body="{\\"currentPassword\\":\\"safe-test-password-123\\",\\"newPassword\\":\\""+changed+"\\"}";
        assertEquals(401,mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
            .content(body)).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(post(path).header("Authorization","Bearer "+first)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.replace(changed,"short"))).andReturn().getResponse().getStatus());
        assertEquals(409,mvc.perform(post(path).header("Authorization","Bearer "+first)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.replace(changed,"safe-test-password-123")))
            .andReturn().getResponse().getStatus());
        var changedResponse=mvc.perform(post(path).header("Authorization","Bearer "+first)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(200,changedResponse.getStatus(),changedResponse.getContentAsString());
        assertTrue(changedResponse.getHeader("Cache-Control").contains("no-store"));
        assertFalse(changedResponse.getContentAsString().contains(changed));
        assertEquals(401,mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization","Bearer "+first)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization","Bearer "+second)).andReturn().getResponse().getStatus());
        assertEquals(2,jdbc.queryForObject("""
            SELECT count(*) FROM staff_sessions WHERE staff_user_id=? AND revoked_at IS NOT NULL
            """,Integer.class,waiter.id()));
        assertEquals(401,mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(waiter.login(),"safe-test-password-123")))
            .andReturn().getResponse().getStatus());
        var login=mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(waiter.login(),changed))).andReturn().getResponse();
        assertEquals(200,login.getStatus());
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs WHERE actor_id=? AND resource_id=?
              AND operation='STAFF_PASSWORD_CHANGED'
            """,Integer.class,waiter.id(),waiter.id()));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs WHERE actor_id=?
              AND (reason LIKE ? OR safe_before_after_diff::text LIKE ?)
            """,Integer.class,waiter.id(),"%"+changed+"%","%"+changed+"%"));
    }

    @Test void wrongCurrentPasswordIsRateLimitedAndDoesNotRevokeSessions() throws Exception {
        var cashier=staff("CASHIER");
        String bearer=loginToken(cashier);
        String path="/api/v1/staff/auth/password/change";
        String body="{\\"currentPassword\\":\\"incorrect-current-password\\",\\"newPassword\\":\\"new-synthetic-password-2026\\"}";
        for(int i=0;i<4;i++) {
            assertEquals(403,mvc.perform(post(path).header("Authorization","Bearer "+bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus());
        }
        assertEquals(429,mvc.perform(post(path).header("Authorization","Bearer "+bearer)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus());
        assertEquals(429,mvc.perform(post(path).header("Authorization","Bearer "+bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("incorrect-current-password","safe-test-password-123")))
            .andReturn().getResponse().getStatus());
        assertEquals(5,jdbc.queryForObject("""
            SELECT failed_attempts FROM staff_password_change_throttles WHERE staff_user_id=?
            """,Integer.class,cashier.id()));
        assertEquals(200,mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization","Bearer "+bearer)).andReturn().getResponse().getStatus());
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs WHERE actor_id=? AND operation='STAFF_PASSWORD_CHANGED'
            """,Integer.class,cashier.id()));
    }

    @Test void concurrentRotationCannotLeaveAnOldPasswordSessionAlive() throws Exception {
        var kitchen=staff("KITCHEN");
        String bearer=loginToken(kitchen);
        String path="/api/v1/staff/auth/password/change";
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> tasks=new java.util.ArrayList<>();
            for(String next:java.util.List.of("synthetic-new-first-2026","synthetic-new-second-2026")) {
                tasks.add(pool.submit(()->{
                    start.await();
                    String body="{\\"currentPassword\\":\\"safe-test-password-123\\",\\"newPassword\\":\\""+next+"\\"}";
                    return mvc.perform(post(path).header("Authorization","Bearer "+bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            var results=java.util.List.of(
                tasks.get(0).get(30,java.util.concurrent.TimeUnit.SECONDS),
                tasks.get(1).get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(results.contains(200),results.toString());
            assertTrue(results.contains(401),results.toString());
            assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE actor_id=? AND operation='STAFF_PASSWORD_CHANGED'
                """,Integer.class,kitchen.id()));
            assertEquals(401,mvc.perform(get("/api/v1/staff/auth/me")
                .header("Authorization","Bearer "+bearer)).andReturn().getResponse().getStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void unauthenticatedAndUnknownBearerAreRejected() throws Exception {
        assertEquals(401, mvc.perform(get("/api/v1/staff/auth/me"))
            .andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization", "Bearer " + "a".repeat(43)))
            .andReturn().getResponse().getStatus());
    }
}
