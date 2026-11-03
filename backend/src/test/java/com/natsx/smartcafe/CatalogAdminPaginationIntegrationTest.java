package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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
class CatalogAdminPaginationIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_catalog_pages").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", postgres::getJdbcUrl);
        props.add("spring.datasource.username", postgres::getUsername);
        props.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private String staffToken(String role) {
        UUID staff=UUID.randomUUID();
        String token=com.natsx.smartcafe.identity.SecureToken.generate();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"catalog-page-" + staff,"synthetic-test-only");
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",
            staff,role);
        jdbc.update("""
            INSERT INTO staff_sessions(token_hash,staff_user_id,expires_at)
            VALUES (?,?,now()+interval '1 hour')
            """,com.natsx.smartcafe.identity.SecureToken.sha256(token),staff);
        return token;
    }

    private JsonNode list(String bearer, int page, String search) throws Exception {
        var response=mvc.perform(get("/api/v1/admin/menu-items/page")
            .header("Authorization","Bearer " + bearer)
            .param("page",Integer.toString(page)).param("q",search))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        assertFalse(response.getContentAsString().contains("passwordHash"));
        assertFalse(response.getContentAsString().contains("guestId"));
        return mapper.readTree(response.getContentAsString()).path("data");
    }

    @Test
    void canSearchEveryProductAfterTheFormer500ItemLimit() throws Exception {
        String token=staffToken("MANAGER");
        String prefix="CATPG" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        UUID category=UUID.randomUUID();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            category,"Synthetic Pages",prefix.toLowerCase(Locale.ROOT));
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr,availability)
            SELECT gen_random_uuid(), ?, ? || lpad(n::text,4,'0'), 12000, 'HIDDEN'
            FROM generate_series(1,505) n
            """,category,prefix + "-");

        JsonNode first=list(token,0,prefix.toLowerCase(Locale.ROOT));
        assertEquals(505,first.path("total").asInt());
        assertEquals(20,first.path("pageSize").asInt());
        assertEquals(20,first.path("items").size());
        assertTrue(first.path("hasNext").asBoolean());
        assertEquals(prefix + "-0001",first.path("items").get(0).path("name").asText());

        JsonNode last=list(token,25,prefix);
        assertEquals(5,last.path("items").size());
        assertFalse(last.path("hasNext").asBoolean());
        assertEquals(prefix + "-0505",last.path("items").get(4).path("name").asText());
        assertEquals(0,list(token,26,prefix).path("items").size());

        // Search allows spaces in category names and matches status labels.
        assertTrue(list(token,0,"Synthetic Pages").path("total").asInt() >= 505);
        assertTrue(list(token,0,"hidden").path("total").asInt() >= 505);

        jdbc.update("INSERT INTO menu_items(id,category_id,name,base_price_idr) VALUES (?,?,?,?)",
            UUID.randomUUID(),prefix + "_SPECIAL",category,10000);
    }

    @Test
    void pageValidationAndStaffRoleBoundaryAreEnforced() throws Exception {
        assertEquals(401,mvc.perform(get("/api/v1/admin/menu-items/page"))
            .andReturn().getResponse().getStatus());
        for (String role : new String[] {"CASHIER","KITCHEN","WAITER"}) {
            var token=staffToken(role);
            assertEquals(403,mvc.perform(get("/api/v1/admin/menu-items/page")
                .header("Authorization","Bearer " + token))
                .andReturn().getResponse().getStatus());
        }
        String admin=staffToken("ADMIN");
        assertEquals(400,mvc.perform(get("/api/v1/admin/menu-items/page?page=1001")
            .header("Authorization","Bearer " + admin)).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/api/v1/admin/menu-items/page")
            .param("q","x".repeat(81))
            .header("Authorization","Bearer " + admin)).andReturn().getResponse().getStatus());
        assertEquals(20,list(admin,0,"").path("pageSize").asInt());
    }
}
