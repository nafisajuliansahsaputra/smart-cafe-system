package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class TableAdminIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_table_admin").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;

    private String value(String json, String key) {
        var matcher=Pattern.compile("\\"" + key + "\\":\\"([^\\"]+)\\"").matcher(json);
        assertTrue(matcher.find(), "Missing " + key);
        return matcher.group(1);
    }

    private String login(String role) throws Exception {
        UUID id=UUID.randomUUID();
        String user="tableadmin-" + id;
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id, user, passwords.encode("synthetic-only-secure-test"));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",id,role);
        var res=mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login\":\"" + user + "\",\"password\":\"synthetic-only-secure-test\"}"))
            .andReturn().getResponse();
        assertEquals(200,res.getStatus(),res.getContentAsString());
        return value(res.getContentAsString(),"accessToken");
    }

    @Test
    void createAndUpdateAreAuditedAndDoNotOverwriteLiveVisits() throws Exception {
        var manager=login("MANAGER");
        var waiter=login("WAITER");
        String code="M7-" + UUID.randomUUID().toString().substring(0,8).toUpperCase();
        String alias=code.toLowerCase();
        var body="{\"tableCode\":\"" + code + "\",\"publicAlias\":\"" +
            alias + "\",\"capacity\":4}";
        assertEquals(401,mvc.perform(get("/api/v1/admin/tables"))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/v1/admin/tables")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/api/v1/admin/tables")
            .header("Authorization","Bearer " + waiter)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus());

        var created=mvc.perform(post("/api/v1/admin/tables")
            .header("Authorization","Bearer " + manager)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
        assertEquals(200,created.getStatus(),created.getContentAsString());
        UUID id=UUID.fromString(value(created.getContentAsString(),"id"));
        assertEquals(409,mvc.perform(post("/api/v1/admin/tables")
            .header("Authorization","Bearer " + manager)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus());
        assertTrue(mvc.perform(get("/api/v1/admin/tables")
            .header("Authorization","Bearer " + manager))
            .andReturn().getResponse().getContentAsString().contains(code));

        var change=mvc.perform(patch("/api/v1/admin/tables/" + id)
            .header("Authorization","Bearer " + manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"capacity\":6,\"status\":\"OUT_OF_SERVICE\"}"))
            .andReturn().getResponse();
        assertEquals(200,change.getStatus(),change.getContentAsString());
        assertEquals("OUT_OF_SERVICE",jdbc.queryForObject(
            "SELECT status FROM cafe_tables WHERE id=?",String.class,id));
        assertEquals(409,mvc.perform(post("/api/v1/waiter/table-visits")
            .header("Authorization","Bearer " + waiter)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableId\":\"" + id + "\"}"))
            .andReturn().getResponse().getStatus());

        assertEquals(200,mvc.perform(patch("/api/v1/admin/tables/" + id)
            .header("Authorization","Bearer " + manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"capacity\":6,\"status\":\"AVAILABLE\"}"))
            .andReturn().getResponse().getStatus());
        var opened=mvc.perform(post("/api/v1/waiter/table-visits")
            .header("Authorization","Bearer " + waiter)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableId\":\"" + id + "\"}"))
            .andReturn().getResponse();
        assertEquals(200,opened.getStatus(),opened.getContentAsString());
        assertEquals(409,mvc.perform(patch("/api/v1/admin/tables/" + id)
            .header("Authorization","Bearer " + manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"capacity\":8,\"status\":\"OUT_OF_SERVICE\"}"))
            .andReturn().getResponse().getStatus());
        assertEquals(6,jdbc.queryForObject(
            "SELECT capacity FROM cafe_tables WHERE id=?",Integer.class,id));
        assertEquals(3,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs WHERE resource_id=?
               AND operation IN ('TABLE_CREATED','TABLE_SETTINGS_CHANGED')
            """, Integer.class,id));
    }

    @Test
    void unsafeTableAliasesAndConfigurationAreRejected() throws Exception {
        var manager=login("ADMIN");
        for (String payload : new String[] {
            "{\"tableCode\":\"A 1\",\"publicAlias\":\"a-1\",\"capacity\":2}",
            "{\"tableCode\":\"A1\",\"publicAlias\":\"../orders\",\"capacity\":2}",
            "{\"tableCode\":\"A1\",\"publicAlias\":\"a1\",\"capacity\":0}"
        }) {
            assertEquals(400,mvc.perform(post("/api/v1/admin/tables")
                .header("Authorization","Bearer " + manager)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn().getResponse().getStatus());
        }
    }
}
