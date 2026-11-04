package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
class TableVisitIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_visits").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @Autowired com.natsx.smartcafe.common.events.OutboxLiveRelay liveRelay;

    private String extract(String json, String field) {
        var matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), "Missing JSON field: " + field);
        return matcher.group(1);
    }

    private String staff(String role) throws Exception {
        UUID id=UUID.randomUUID();
        String login="visit-" + id;
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",id,login,
            passwords.encode("safe-test-password-123"));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",id,role);
        var response=mvc.perform(post("/api/v1/staff/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"login\":\"" + login + "\",\"password\":\"safe-test-password-123\"}"))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus());
        return extract(response.getContentAsString(),"accessToken");
    }

    private record Table(UUID id, String alias) {}
    private Table table() {
        UUID id=UUID.randomUUID();
        String alias="demo-" + id;
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            id,id.toString(),alias,4);
        return new Table(id,alias);
    }

    private String open(String bearer, Table table) throws Exception {
        var response=mvc.perform(post("/api/v1/waiter/table-visits")
            .header("Authorization","Bearer " + bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableId\":\"" + table.id() + "\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        return response.getContentAsString();
    }

    private org.springframework.mock.web.MockHttpServletResponse join(Table table,String proof)
        throws Exception {
        return mvc.perform(post("/api/v1/table-visits/join").contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableAlias\":\"" + table.alias() +
                "\",\"joinProof\":\"" + proof + "\",\"displayName\":\"Synthetic Visitor\"}"))
            .andReturn().getResponse();
    }

    @Test void qrOnlyCannotJoinAndClosedVisitRevokesGuestAndOldProof() throws Exception {
        var table=table();
        var waiter=staff("WAITER");
        var opened=open(waiter,table);
        var visitId=extract(opened,"id");
        var proof=extract(opened,"joinProof");
        assertFalse(proof.isBlank());
        var entry=mvc.perform(get("/api/v1/tables/" + table.alias() + "/entry")).andReturn().getResponse();
        assertEquals(200,entry.getStatus());
        assertTrue(entry.getContentAsString().contains("activeVisit"));
        assertFalse(entry.getContentAsString().contains(proof));
        assertEquals(404,join(table,"a".repeat(43)).getStatus());
        var guest=join(table,proof);
        assertEquals(200,guest.getStatus());
        String guestToken=extract(guest.getContentAsString(),"accessToken");
        assertEquals(200,mvc.perform(get("/api/v1/me/visit")
            .header("Authorization","Bearer " + guestToken)).andReturn().getResponse().getStatus());
        // GuestBearerFilter intentionally ignores staff-auth routes, so a guest
        // bearer is UNAUTHENTICATED here. The additional staff-role gate ensures
        // future filter changes cannot turn it into a StaffPrincipal.
        assertEquals(401,mvc.perform(get("/api/v1/staff/auth/me")
            .header("Authorization","Bearer " + guestToken)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(post("/api/v1/staff/auth/logout")
            .header("Authorization","Bearer " + guestToken)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get("/api/v1/me/visit")
            .header("Authorization","Bearer " + guestToken)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/v1/me/visit"))
            .andReturn().getResponse().getStatus());
        var closed=mvc.perform(post("/api/v1/waiter/table-visits/" + visitId + "/close")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse();
        assertEquals(200,closed.getStatus(),closed.getContentAsString());
        assertEquals(401,mvc.perform(get("/api/v1/me/visit")
            .header("Authorization","Bearer " + guestToken)).andReturn().getResponse().getStatus());
        assertEquals(404,join(table,proof).getStatus());
        var second=open(waiter,table);
        assertNotEquals(visitId,extract(second,"id"));
        assertNotEquals(proof,extract(second,"joinProof"));
    }

    @Test void duplicateOpenAndRotatedProofAreProtected() throws Exception {
        var table=table();
        var waiter=staff("WAITER");
        var opened=open(waiter,table);
        var id=extract(opened,"id");
        var otherWaiter=staff("WAITER");
        assertEquals(403,mvc.perform(post("/api/v1/waiter/table-visits/" + id + "/join-proof")
            .header("Authorization","Bearer " + otherWaiter)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/api/v1/waiter/table-visits/" + id + "/close")
            .header("Authorization","Bearer " + otherWaiter)).andReturn().getResponse().getStatus());
        var oldProof=extract(opened,"joinProof");
        assertEquals(409,mvc.perform(post("/api/v1/waiter/table-visits")
            .header("Authorization","Bearer " + waiter).contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableId\":\"" + table.id() + "\"}")).andReturn().getResponse().getStatus());
        var rotated=mvc.perform(post("/api/v1/waiter/table-visits/" + id + "/join-proof")
            .header("Authorization","Bearer " + waiter)).andReturn().getResponse();
        assertEquals(200,rotated.getStatus());
        assertEquals(404,join(table,oldProof).getStatus());
        assertEquals(200,join(table,extract(rotated.getContentAsString(),"joinProof")).getStatus());
    }

    @Test void kitchenCannotOpenVisitOrUseStaffBearerAsGuest() throws Exception {
        var table=table();
        var kitchen=staff("KITCHEN");
        assertEquals(403,mvc.perform(post("/api/v1/waiter/table-visits")
            .header("Authorization","Bearer " + kitchen).contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableId\":\"" + table.id() + "\"}")).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/v1/me/visit")
            .header("Authorization","Bearer " + kitchen)).andReturn().getResponse().getStatus());
    }

    @Test void waiterTableInventoryHidesForeignVisitIdsAndAllJoinProofs() throws Exception {
        var table = table();
        var waiter = staff("WAITER");
        var otherWaiter = staff("WAITER");
        var cashier = staff("CASHIER");
        var kitchen = staff("KITCHEN");

        var before = mvc.perform(get("/api/v1/waiter/tables")
            .header("Authorization", "Bearer " + waiter)).andReturn().getResponse();
        assertEquals(200, before.getStatus());
        assertTrue(before.getContentAsString().contains(table.id().toString()));
        assertTrue(before.getContentAsString().contains("\"activeVisit\":false"));

        var opened = open(waiter, table);
        String visitId = extract(opened, "id");
        String proof = extract(opened, "joinProof");

        var mine = mvc.perform(get("/api/v1/waiter/tables")
            .header("Authorization", "Bearer " + waiter)).andReturn().getResponse();
        assertEquals(200, mine.getStatus());
        assertTrue(mine.getHeader("Cache-Control").contains("no-store"));
        assertTrue(mine.getContentAsString().contains("\"activeVisit\":true"));
        assertTrue(mine.getContentAsString().contains(visitId));
        assertFalse(mine.getContentAsString().contains(proof));
        assertFalse(mine.getContentAsString().contains("joinProof"));

        var foreign = mvc.perform(get("/api/v1/waiter/tables")
            .header("Authorization", "Bearer " + otherWaiter)).andReturn().getResponse();
        assertEquals(200, foreign.getStatus());
        assertTrue(foreign.getContentAsString().contains("\"activeVisit\":true"));
        assertFalse(foreign.getContentAsString().contains(visitId));
        assertFalse(foreign.getContentAsString().contains(proof));

        var supervised = mvc.perform(get("/api/v1/waiter/tables")
            .header("Authorization", "Bearer " + cashier)).andReturn().getResponse();
        assertEquals(200, supervised.getStatus());
        assertTrue(supervised.getContentAsString().contains(visitId));
        assertFalse(supervised.getContentAsString().contains(proof));

        assertEquals(403, mvc.perform(get("/api/v1/waiter/tables")
            .header("Authorization", "Bearer " + kitchen)).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/v1/waiter/tables"))
            .andReturn().getResponse().getStatus());

        assertEquals(200, mvc.perform(post("/api/v1/waiter/table-visits/" + visitId + "/close")
            .header("Authorization", "Bearer " + waiter)).andReturn().getResponse().getStatus());
        var closed = mvc.perform(get("/api/v1/waiter/tables")
            .header("Authorization", "Bearer " + waiter)).andReturn().getResponse();
        assertEquals(200, closed.getStatus());
        assertFalse(closed.getContentAsString().contains(visitId));
    }


    @Test void publicJoinLimiterCommitsFailedAttemptsAndIgnoresSpoofedForwardedIp()
        throws Exception {
        var table=table();
        var waiter=staff("WAITER");
        var opened=open(waiter,table);
        var proof=extract(opened,"joinProof");
        String invalid="{\"tableAlias\":\"" + table.alias()
            + "\",\"joinProof\":\"" + "x".repeat(43)
            + "\",\"displayName\":\"Synthetic Rate Test\"}";
        String valid="{\"tableAlias\":\"" + table.alias()
            + "\",\"joinProof\":\"" + proof
            + "\",\"displayName\":\"Synthetic Rate Test\"}";

        for (int i=0; i<24; i++) {
            var response=mvc.perform(post("/api/v1/table-visits/join")
                .with(req -> { req.setRemoteAddr("203.0.113.81"); return req; })
                .header("X-Forwarded-For", "198.51.100." + (i%100))
                .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andReturn().getResponse();
            assertEquals(404,response.getStatus(),response.getContentAsString());
        }
        var blocked=mvc.perform(post("/api/v1/table-visits/join")
            .with(req -> { req.setRemoteAddr("203.0.113.81"); return req; })
            .header("X-Forwarded-For","192.0.2.1")
            .contentType(MediaType.APPLICATION_JSON).content(valid))
            .andReturn().getResponse();
        assertEquals(429,blocked.getStatus(),blocked.getContentAsString());
        assertEquals("600",blocked.getHeader("Retry-After"));
        assertTrue(blocked.getHeader("Cache-Control").contains("no-store"));
        assertTrue(blocked.getContentAsString().contains("RATE_LIMITED"));
        assertFalse(blocked.getContentAsString().contains(proof));
        assertFalse(blocked.getContentAsString().contains("accessToken"));

        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM visit_guests WHERE visit_id=?",Integer.class,
            UUID.fromString(extract(opened,"id"))));
        assertTrue(jdbc.queryForObject(
            "SELECT count(*) FROM guest_join_throttles WHERE attempts>=25",
            Integer.class)>0);
        assertEquals(200,mvc.perform(post("/api/v1/table-visits/join")
            .with(req -> { req.setRemoteAddr("203.0.113.82"); return req; })
            .contentType(MediaType.APPLICATION_JSON).content(valid))
            .andReturn().getResponse().getStatus());

        var nextTable=table();
        var nextOpen=open(waiter,nextTable);
        var otherValid=mvc.perform(post("/api/v1/table-visits/join")
            .with(req -> { req.setRemoteAddr("203.0.113.81"); return req; })
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tableAlias\":\"" + nextTable.alias() + "\",\"joinProof\":\""
                + extract(nextOpen,"joinProof") + "\",\"displayName\":\"Synthetic Other\"}"))
            .andReturn().getResponse();
        assertEquals(200,otherValid.getStatus(),otherValid.getContentAsString());
    }


    private long tableCursor(String token) throws Exception {
        var response=mvc.perform(get("/api/v1/waiter/live-cursor")
            .param("topic","tables").header("Authorization","Bearer "+token))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        var matcher=Pattern.compile("\"cursor\":(\\d+)").matcher(response.getContentAsString());
        assertTrue(matcher.find(),"Missing authorized table cursor");
        return Long.parseLong(matcher.group(1));
    }

    @Test void tableLifecycleEventsAreDurableAndProofRotationIsOwnerScoped() throws Exception {
        var table=table();
        var owner=staff("WAITER");
        var otherWaiter=staff("WAITER");
        var cashier=staff("CASHIER");
        var kitchen=staff("KITCHEN");

        // Reset the test fixture's prior unpublished table events before
        // establishing baselines, without touching the business outbox marker.
        liveRelay.dispatchBatch();
        long beforeOwner=tableCursor(owner);
        long beforeOther=tableCursor(otherWaiter);
        long beforeSupervisor=tableCursor(cashier);
        assertEquals(403,mvc.perform(get("/api/v1/waiter/live-cursor")
            .param("topic","tables").header("Authorization","Bearer "+kitchen))
            .andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/api/v1/waiter/live-cursor")
            .param("topic","tables").param("orderId",UUID.randomUUID().toString())
            .header("Authorization","Bearer "+owner))
            .andReturn().getResponse().getStatus());

        String opened=open(owner,table);
        UUID visit=UUID.fromString(extract(opened,"id"));
        String secret=extract(opened,"joinProof");
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events
            WHERE aggregate_type='TABLE_VISIT' AND aggregate_id=? AND
                  event_type='TABLE_VISIT_OPENED' AND live_published_at IS NULL
            """,Integer.class,visit));
        assertTrue(liveRelay.dispatchBatch()>0);
        long openedCursor=tableCursor(owner);
        assertTrue(openedCursor>beforeOwner);
        assertTrue(tableCursor(otherWaiter)>beforeOther);
        assertTrue(tableCursor(cashier)>beforeSupervisor);

        var rotation=mvc.perform(post("/api/v1/waiter/table-visits/"+visit+"/join-proof")
            .header("Authorization","Bearer "+owner)).andReturn().getResponse();
        assertEquals(200,rotation.getStatus(),rotation.getContentAsString());
        assertEquals(403,mvc.perform(post("/api/v1/waiter/table-visits/"+visit+"/join-proof")
            .header("Authorization","Bearer "+otherWaiter)).andReturn().getResponse().getStatus());
        long foreignBefore=tableCursor(otherWaiter);
        long supervisorBefore=tableCursor(cashier);
        assertTrue(liveRelay.dispatchBatch()>0);
        long rotatedCursor=tableCursor(owner);
        assertTrue(rotatedCursor>openedCursor);
        assertEquals(foreignBefore,tableCursor(otherWaiter));
        assertTrue(tableCursor(cashier)>supervisorBefore);

        var stored=jdbc.queryForMap("""
            SELECT event_type,table_id FROM live_table_signals
            WHERE visit_id=? AND event_type='TABLE_JOIN_PROOF_ROTATED'
            """,visit);
        assertEquals(table.id(),stored.get("table_id"));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events
            WHERE aggregate_id=? AND live_published_at IS NULL
            """,Integer.class,visit));
        var json=jdbc.queryForObject("""
            SELECT string_agg(payload_safe::text,',') FROM outbox_events
            WHERE aggregate_type='TABLE_VISIT' AND aggregate_id=?
            """,String.class,visit);
        assertFalse(json.contains(secret));
        assertFalse(json.contains(extract(rotation.getContentAsString(),"joinProof")));

        long foreignBeforeClose=tableCursor(otherWaiter);
        assertEquals(200,mvc.perform(post("/api/v1/waiter/table-visits/"+visit+"/close")
            .header("Authorization","Bearer "+owner)).andReturn().getResponse().getStatus());
        assertTrue(liveRelay.dispatchBatch()>0);
        assertTrue(tableCursor(otherWaiter)>foreignBeforeClose);
        assertTrue(tableCursor(owner)>rotatedCursor);
        assertEquals(3,jdbc.queryForObject("""
            SELECT count(*) FROM live_table_signals WHERE visit_id=?
            """,Integer.class,visit));
        assertEquals(3,jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events WHERE aggregate_type='TABLE_VISIT'
            AND aggregate_id=? AND live_published_at IS NOT NULL AND processed_at IS NULL
            """,Integer.class,visit));
        assertEquals(0,liveRelay.dispatchBatch());
    }

}
