package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Locale;
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

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class TableRegistryPaginationIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_registry_pages")
        .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;

    private String login(String role) throws Exception {
        UUID id=UUID.randomUUID();
        String login="pages-" + id;
        String password="synthetic-registry-page-password";
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id, login, passwords.encode(password));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)", id,role);
        var response=mvc.perform(post("/api/v1/staff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}"))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        return mapper.readTree(response.getContentAsString()).path("data")
            .path("accessToken").asText();
    }

    private JsonNode page(String token, int number, String q) throws Exception {
        var response=mvc.perform(get("/api/v1/admin/tables/registry")
            .param("page", Integer.toString(number)).param("q", q)
            .header("Authorization","Bearer " + token))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        assertFalse(response.getContentAsString().contains("joinProof"));
        assertFalse(response.getContentAsString().contains("manageableVisitId"));
        assertFalse(response.getContentAsString().contains("guestId"));
        return mapper.readTree(response.getContentAsString()).path("data");
    }

    @Test
    void managerCanSearchAndPageWithoutSilentlyTruncatingOrWildcardMatching() throws Exception {
        String manager=login("MANAGER");
        String group="PG" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        for (int i=1;i<=28;i++) {
            String code=group + "-" + String.format(Locale.ROOT,"%03d",i);
            jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
                UUID.randomUUID(), code,code.toLowerCase(Locale.ROOT),4);
        }
        var first=page(manager,0,group);
        assertEquals(0,first.path("page").asInt());
        assertEquals(25,first.path("pageSize").asInt());
        assertEquals(28,first.path("total").asInt());
        assertTrue(first.path("hasNext").asBoolean());
        assertEquals(25,first.path("items").size());
        assertTrue(first.path("items").get(0).path("tableCode").asText().endsWith("-001"));
        var second=page(manager,1,group.toLowerCase(Locale.ROOT));
        assertEquals(3,second.path("items").size());
        assertFalse(second.path("hasNext").asBoolean());
        assertTrue(second.path("items").get(2).path("tableCode").asText().endsWith("-028"));
        var afterLast=page(manager,2,group);
        assertEquals(0,afterLast.path("items").size());
        assertFalse(afterLast.path("hasNext").asBoolean());

        // Explicitly exceed the old 250-table waiter-board cap, then prove the
        // final manager page and QR batch are still reachable through the API.
        for (int i=29;i<=253;i++) {
            String code=group + "-" + String.format(Locale.ROOT,"%03d",i);
            jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
                UUID.randomUUID(), code,code.toLowerCase(Locale.ROOT),4);
        }
        var beyondLimit=page(manager,10,group);
        assertEquals(253,beyondLimit.path("total").asInt());
        assertEquals(3,beyondLimit.path("items").size());
        assertFalse(beyondLimit.path("hasNext").asBoolean());
        assertTrue(beyondLimit.path("items").get(2).path("tableCode").asText().endsWith("-253"));

        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            UUID.randomUUID(),group + "_SPECIAL",group.toLowerCase(Locale.ROOT) + "-special",4);
        var literal=page(manager,0,"_SPECIAL");
        assertEquals(1,literal.path("total").asInt());
        assertEquals(1,literal.path("items").size());
        assertEquals(group + "_SPECIAL",literal.path("items").get(0).path("tableCode").asText());

        assertEquals(400,mvc.perform(get("/api/v1/admin/tables/registry?page=1001")
            .header("Authorization","Bearer " + manager))
            .andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/api/v1/admin/tables/registry?q=%25")
            .header("Authorization","Bearer " + manager))
            .andReturn().getResponse().getStatus());
    }

    @Test
    void tableRegistryIsDeniedToUnauthenticatedAndOrdinaryStaff() throws Exception {
        assertEquals(401,mvc.perform(get("/api/v1/admin/tables/registry"))
            .andReturn().getResponse().getStatus());
        for (String role : new String[] {"CASHIER","WAITER","KITCHEN"}) {
            String token=login(role);
            assertEquals(403,mvc.perform(get("/api/v1/admin/tables/registry")
                .header("Authorization","Bearer " + token))
                .andReturn().getResponse().getStatus());
        }
        String admin=login("ADMIN");
        assertEquals(25,page(admin,0,"").path("pageSize").asInt());
    }
    @Test
    void waiterFloorPagesPast250AndScopesLiveVisitToOwnerOrSupervisor() throws Exception {
        String owner=login("WAITER");
        String otherWaiter=login("WAITER");
        String cashier=login("CASHIER");
        String kitchen=login("KITCHEN");
        String group="WF" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        UUID firstTable=null;
        for (int i=1; i<=253; i++) {
            UUID id=UUID.randomUUID();
            if (i==1) firstTable=id;
            String code=group + "-" + String.format(Locale.ROOT,"%03d",i);
            jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
                id,code,code.toLowerCase(Locale.ROOT),4);
        }
        var first=mvc.perform(get("/api/v1/waiter/tables/page")
            .param("page","0").param("q",group)
            .header("Authorization","Bearer " + owner)).andReturn().getResponse();
        assertEquals(200,first.getStatus());
        assertTrue(first.getHeader("Cache-Control").contains("no-store"));
        assertFalse(first.getContentAsString().contains("joinProof"));
        var data=mapper.readTree(first.getContentAsString()).path("data");
        assertEquals(253,data.path("total").asInt());
        assertEquals(25,data.path("pageSize").asInt());
        assertEquals(25,data.path("items").size());
        assertTrue(data.path("hasNext").asBoolean());
        var last=mvc.perform(get("/api/v1/waiter/tables/page")
            .param("page","10").param("q",group.toLowerCase(Locale.ROOT))
            .header("Authorization","Bearer " + owner)).andReturn().getResponse();
        assertEquals(200,last.getStatus());
        var lastData=mapper.readTree(last.getContentAsString()).path("data");
        assertEquals(3,lastData.path("items").size());
        assertFalse(lastData.path("hasNext").asBoolean());
        assertTrue(lastData.path("items").get(2).path("tableCode")
            .asText().endsWith("-253"));

        var opened=mvc.perform(post("/api/v1/waiter/table-visits")
            .header("Authorization","Bearer " + owner)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\\\"tableId\\\":\\\"" + firstTable + "\\\"}"))
            .andReturn().getResponse();
        assertEquals(200,opened.getStatus(),opened.getContentAsString());
        String visitId=mapper.readTree(opened.getContentAsString()).path("data")
            .path("id").asText();
        for (var actor : new String[] {owner,otherWaiter,cashier}) {
            var scoped=mvc.perform(get("/api/v1/waiter/tables/page")
                .param("q",group + "-001")
                .header("Authorization","Bearer " + actor)).andReturn().getResponse();
            assertEquals(200,scoped.getStatus());
            assertFalse(scoped.getContentAsString().contains("joinProof"));
            var row=mapper.readTree(scoped.getContentAsString()).path("data")
                .path("items").get(0);
            assertTrue(row.path("activeVisit").asBoolean());
            if (actor.equals(otherWaiter)) {
                assertTrue(row.path("manageableVisitId").isNull());
                assertTrue(row.path("proofExpiresAt").isNull());
            } else {
                assertEquals(visitId,row.path("manageableVisitId").asText());
                assertFalse(row.path("proofExpiresAt").isNull());
            }
        }
        assertEquals(400,mvc.perform(get("/api/v1/waiter/tables/page")
            .param("page","1001")
            .header("Authorization","Bearer " + owner)).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/api/v1/waiter/tables/page")
            .param("q","%")
            .header("Authorization","Bearer " + owner)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/waiter/tables/page"))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/v1/waiter/tables/page")
            .header("Authorization","Bearer " + kitchen)).andReturn().getResponse().getStatus());
    }

}
