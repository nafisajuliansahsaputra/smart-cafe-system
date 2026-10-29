package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
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
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-isolated-game-tests-more-than-thirty-two-bytes")
class GameLoanIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_game_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder password;
    @Autowired com.natsx.smartcafe.common.events.OutboxLiveRelay relay;

    private String field(String json,String name) {
        var m=Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(),"Missing JSON "+name+": "+json);
        return m.group(1);
    }
    private record User(String token,UUID id) {}
    private User staff(String role) throws Exception {
        UUID id=UUID.randomUUID();
        String login="game-"+id;
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id,login,password.encode("synthetic-only-password"));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",id,role);
        var r=postJson("/api/v1/staff/auth/login",null,
            "{\"login\":\""+login+"\",\"password\":\"synthetic-only-password\"}");
        assertEquals(200,r.getStatus(),r.getContentAsString());
        return new User(field(r.getContentAsString(),"accessToken"),id);
    }
    private record Visit(UUID id,String tableAlias,String proof) {}
    private Visit open(User waiter) throws Exception {
        UUID table=UUID.randomUUID();
        String alias="game-"+table;
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,4)",
            table,table.toString(),alias);
        var r=postJson("/api/v1/waiter/table-visits",waiter.token(),
            "{\"tableId\":\""+table+"\"}");
        assertEquals(200,r.getStatus(),r.getContentAsString());
        return new Visit(UUID.fromString(field(r.getContentAsString(),"id")),alias,
            field(r.getContentAsString(),"joinProof"));
    }
    private User join(Visit visit) throws Exception {
        var r=postJson("/api/v1/table-visits/join",null,
            "{\"tableAlias\":\""+visit.tableAlias()+"\",\"joinProof\":\""+
            visit.proof()+"\",\"displayName\":\"Synthetic game borrower\"}");
        assertEquals(200,r.getStatus(),r.getContentAsString());
        return new User(field(r.getContentAsString(),"accessToken"),
            UUID.fromString(field(r.getContentAsString(),"guestId")));
    }
    private org.springframework.mock.web.MockHttpServletResponse postJson(
        String path,String token,String json) throws Exception {
        var req=post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token!=null) req.header("Authorization","Bearer "+token);
        return mvc.perform(req).andReturn().getResponse();
    }
    private org.springframework.mock.web.MockHttpServletResponse postEmpty(
        String path,String token) throws Exception {
        return mvc.perform(post(path).header("Authorization","Bearer "+token))
            .andReturn().getResponse();
    }
    private org.springframework.mock.web.MockHttpServletResponse getAs(
        String path,String token) throws Exception {
        var req=get(path);
        if (token!=null) req.header("Authorization","Bearer "+token);
        return mvc.perform(req).andReturn().getResponse();
    }
    private UUID createGame(User manager,int numberOfCopies) throws Exception {
        String name="UNO Test "+UUID.randomUUID();
        var r=postJson("/api/v1/admin/games",manager.token(),
            "{\"name\":\""+name+"\",\"description\":\"Physical deck\"}");
        assertEquals(200,r.getStatus(),r.getContentAsString());
        UUID id=UUID.fromString(field(r.getContentAsString(),"id"));
        for (int i=0; i<numberOfCopies; i++) {
            var copy=postJson("/api/v1/admin/games/"+id+"/copies",manager.token(),
                "{\"copyCode\":\"UNO-"+UUID.randomUUID()+"\"}");
            assertEquals(200,copy.getStatus(),copy.getContentAsString());
        }
        return id;
    }
    private UUID requestLoan(User guest,UUID gameId) throws Exception {
        var r=postJson("/api/v1/me/game-loans",guest.token(),
            "{\"gameId\":\""+gameId+"\"}");
        assertEquals(200,r.getStatus(),r.getContentAsString());
        return UUID.fromString(field(r.getContentAsString(),"id"));
    }

    @Test void physicalUnitRequiresWaiterConfirmationAndBlocksTableClosure() throws Exception {
        User manager=staff("MANAGER"),waiter=staff("WAITER"),
             otherWaiter=staff("WAITER"),kitchen=staff("KITCHEN");
        UUID game=createGame(manager,1);
        Visit visit=open(waiter);
        User guest=join(visit),otherGuest=join(visit);

        assertEquals(401,getAs("/api/v1/me/games",null).getStatus());
        assertEquals(403,getAs("/api/v1/me/games",waiter.token()).getStatus());
        assertEquals(403,getAs("/api/v1/waiter/game-loans",kitchen.token()).getStatus());
        assertEquals(403,postJson("/api/v1/admin/games",waiter.token(),
            "{\"name\":\"Unauthorized catalog\"}").getStatus());
        assertEquals(200,getAs("/api/v1/me/games",guest.token()).getStatus());
        assertTrue(getAs("/api/v1/me/games",guest.token()).getContentAsString()
            .contains("\"availableCopies\":1"));

        UUID loan=requestLoan(guest,game);
        assertEquals(409,postJson("/api/v1/me/game-loans",otherGuest.token(),
            "{\"gameId\":\""+game+"\"}").getStatus());
        var foreign=getAs("/api/v1/me/game-loans",otherGuest.token());
        assertEquals(200,foreign.getStatus());
        assertFalse(foreign.getContentAsString().contains(loan.toString()));
        assertEquals(403,postEmpty("/api/v1/waiter/game-loans/"+loan+"/assign",
            otherWaiter.token()).getStatus());
        assertEquals(403,postEmpty("/api/v1/waiter/game-loans/"+loan+"/assign",
            kitchen.token()).getStatus());
        assertEquals(409,postEmpty("/api/v1/waiter/table-visits/"+visit.id()+"/close",
            waiter.token()).getStatus());

        var assigned=postEmpty("/api/v1/waiter/game-loans/"+loan+"/assign",waiter.token());
        assertEquals(200,assigned.getStatus(),assigned.getContentAsString());
        String copyCode=field(assigned.getContentAsString(),"copyCode");
        assertEquals(409,postEmpty("/api/v1/waiter/game-loans/"+loan+"/assign",
            waiter.token()).getStatus());
        assertEquals(409,postJson("/api/v1/waiter/game-loans/"+loan+"/handover",
            waiter.token(),"{\"copyCode\":\"WRONG-UNIT\"}").getStatus());
        assertEquals(409,postEmpty("/api/v1/waiter/table-visits/"+visit.id()+"/close",
            waiter.token()).getStatus());

        var handed=postJson("/api/v1/waiter/game-loans/"+loan+"/handover",
            waiter.token(),"{\"copyCode\":\""+copyCode+"\"}");
        assertEquals(200,handed.getStatus(),handed.getContentAsString());
        assertTrue(handed.getContentAsString().contains("IN_USE"));
        assertEquals(409,postEmpty("/api/v1/waiter/table-visits/"+visit.id()+"/close",
            waiter.token()).getStatus());
        assertEquals(409,postJson("/api/v1/waiter/game-loans/"+loan+"/return",
            waiter.token(),"{\"copyCode\":\"WRONG-UNIT\"}").getStatus());
        assertEquals(200,postJson("/api/v1/waiter/game-loans/"+loan+"/return",
            waiter.token(),"{\"copyCode\":\""+copyCode+"\"}").getStatus());
        assertEquals(409,postJson("/api/v1/waiter/game-loans/"+loan+"/return",
            waiter.token(),"{\"copyCode\":\""+copyCode+"\"}").getStatus());
        assertEquals(200,postEmpty("/api/v1/waiter/table-visits/"+visit.id()+"/close",
            waiter.token()).getStatus());
        assertEquals(401,getAs("/api/v1/me/game-loans",guest.token()).getStatus());
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM audit_logs
            WHERE resource_type='GAME_LOAN' AND resource_id=?
              AND operation='GAME_COPY_RETURNED_INSPECTED'
            """,Integer.class,loan));
    }

    @Test void concurrentLastPhysicalCopyCannotBeAssignedTwice() throws Exception {
        User manager=staff("MANAGER"),waiterA=staff("WAITER"),waiterB=staff("WAITER");
        UUID game=createGame(manager,1);
        Visit a=open(waiterA),b=open(waiterB);
        UUID loanA=requestLoan(join(a),game),loanB=requestLoan(join(b),game);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            CompletableFuture<Integer> one=CompletableFuture.supplyAsync(() -> {
                try { start.await(); return postEmpty("/api/v1/waiter/game-loans/"+loanA+
                    "/assign",waiterA.token()).getStatus(); }
                catch (Exception e) { throw new RuntimeException(e); }
            },executor);
            CompletableFuture<Integer> two=CompletableFuture.supplyAsync(() -> {
                try { start.await(); return postEmpty("/api/v1/waiter/game-loans/"+loanB+
                    "/assign",waiterB.token()).getStatus(); }
                catch (Exception e) { throw new RuntimeException(e); }
            },executor);
            start.countDown();
            var statuses=java.util.List.of(one.get(),two.get());
            assertEquals(1,statuses.stream().filter(s->s==200).count());
            assertEquals(1,statuses.stream().filter(s->s==409).count());
            assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM physical_game_loans
                WHERE game_id=? AND status='ASSIGNED'
                """,Integer.class,game));
        }
    }

    @Test void cancelledUnassignedRequestUnblocksVisitAndCannotBeHandedOver() throws Exception {
        User manager=staff("MANAGER"),waiter=staff("WAITER");
        UUID game=createGame(manager,0);
        Visit visit=open(waiter);
        UUID loan=requestLoan(join(visit),game);
        assertEquals(409,postEmpty("/api/v1/waiter/game-loans/"+loan+"/assign",
            waiter.token()).getStatus());
        assertEquals(409,postEmpty("/api/v1/waiter/table-visits/"+visit.id()+"/close",
            waiter.token()).getStatus());
        assertEquals(200,postEmpty("/api/v1/waiter/game-loans/"+loan+"/cancel",
            waiter.token()).getStatus());
        assertEquals(409,postEmpty("/api/v1/waiter/game-loans/"+loan+"/cancel",
            waiter.token()).getStatus());
        assertEquals(200,postEmpty("/api/v1/waiter/table-visits/"+visit.id()+"/close",
            waiter.token()).getStatus());
    }

    private long liveCursor(String path,String token) throws Exception {
        var r=getAs(path,token);
        assertEquals(200,r.getStatus(),r.getContentAsString());
        var m=Pattern.compile("\\\"cursor\\\":([0-9]+)").matcher(r.getContentAsString());
        assertTrue(m.find(),"Missing durable live cursor: "+r.getContentAsString());
        return Long.parseLong(m.group(1));
    }

    @Test void liveGameCursorIsDurableAndScopedToGuestAndResponsibleWaiter() throws Exception {
        User manager=staff("MANAGER"),waiter=staff("WAITER"),
             foreignWaiter=staff("WAITER"),kitchen=staff("KITCHEN");
        Visit visit=open(waiter),foreignVisit=open(foreignWaiter);
        User guest=join(visit),otherGuestSameVisit=join(visit),foreignGuest=join(foreignVisit);
        UUID game=createGame(manager,1);
        relay.dispatchBatch(); // Publish prior table opens before taking baseline.
        String guestPath="/api/v1/me/live-cursor?topic=games";
        String staffPath="/api/v1/waiter/live-cursor?topic=games";
        assertEquals(403,getAs(staffPath,kitchen.token()).getStatus());
        // Guest Bearer is not a staff authentication credential: fail closed (401).
        assertEquals(401,getAs(staffPath,guest.token()).getStatus());
        assertEquals(400,getAs(guestPath+"&orderId="+UUID.randomUUID(),guest.token()).getStatus());
        long beforeGuest=liveCursor(guestPath,guest.token());
        long beforeWaiter=liveCursor(staffPath,waiter.token());
        long beforeForeignWaiter=liveCursor(staffPath,foreignWaiter.token());
        long beforeSameVisit=liveCursor(guestPath,otherGuestSameVisit.token());
        long beforeForeignGuest=liveCursor(guestPath,foreignGuest.token());

        UUID loan=requestLoan(guest,game);
        assertTrue(relay.dispatchBatch()>0);
        long first=liveCursor(guestPath,guest.token());
        assertTrue(first>beforeGuest);
        assertTrue(liveCursor(staffPath,waiter.token())>beforeWaiter);
        assertEquals(beforeForeignWaiter,liveCursor(staffPath,foreignWaiter.token()));
        assertEquals(beforeSameVisit,liveCursor(guestPath,otherGuestSameVisit.token()));
        assertEquals(beforeForeignGuest,liveCursor(guestPath,foreignGuest.token()));
        assertEquals(first,liveCursor(guestPath,guest.token())); // cursor is restart-safe

        var assigned=postEmpty("/api/v1/waiter/game-loans/"+loan+"/assign",waiter.token());
        assertEquals(200,assigned.getStatus(),assigned.getContentAsString());
        String code=field(assigned.getContentAsString(),"copyCode");
        assertTrue(relay.dispatchBatch()>0);
        long afterAssign=liveCursor(guestPath,guest.token());
        assertTrue(afterAssign>first);
        assertEquals(200,postJson("/api/v1/waiter/game-loans/"+loan+"/handover",
            waiter.token(),"{\"copyCode\":\""+code+"\"}").getStatus());
        assertTrue(relay.dispatchBatch()>0);
        assertTrue(liveCursor(guestPath,guest.token())>afterAssign);
        assertEquals(200,postJson("/api/v1/waiter/game-loans/"+loan+"/return",
            waiter.token(),"{\"copyCode\":\""+code+"\"}").getStatus());
        assertTrue(relay.dispatchBatch()>0);
        assertEquals(4,jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events
            WHERE aggregate_type='GAME_LOAN' AND aggregate_id=?
              AND live_published_at IS NOT NULL AND processed_at IS NULL
            """,Integer.class,loan));
        assertEquals(beforeForeignWaiter,liveCursor(staffPath,foreignWaiter.token()));
        assertEquals(beforeSameVisit,liveCursor(guestPath,otherGuestSameVisit.token()));
    }
}
