package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.natsx.smartcafe.identity.StaffAuthService;
import com.natsx.smartcafe.identity.StaffPrincipal;
import com.natsx.smartcafe.ops.CashDrawerShiftService;
import com.natsx.smartcafe.common.api.ApiFailureException;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class CashDrawerShiftIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_shifts").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @Autowired StaffAuthService auth;
    @Autowired CashDrawerShiftService shifts;

    private record Actor(StaffPrincipal principal,String token) {}
    private Actor staff(String role) {
        UUID id = UUID.randomUUID();
        String login = "till-" + id;
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id,login,encoder.encode("shift-e2e-secret-2026"));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,?)",id,role);
        var logged = auth.login(login,"shift-e2e-secret-2026");
        assertNotNull(logged.token());
        return new Actor(auth.authenticate(logged.token()),logged.token());
    }

    @Test void singleActiveShiftReplayAndImmutableCashDiscrepancy() throws Exception {
        var cashier=staff("CASHIER");
        var manager=staff("MANAGER");
        var otherCashier=staff("CASHIER");
        var waiter=staff("WAITER");
        var openingKey="shift-open-"+UUID.randomUUID();
        var first=shifts.open(cashier.principal(),10000,openingKey,"test-open");
        assertEquals("OPEN",first.state());
        assertEquals(10000,first.expectedDrawerIdr());
        assertEquals(first.id(),shifts.open(cashier.principal(),10000,openingKey,"test-replay").id());
        assertEquals(409,assertThrows(ApiFailureException.class,()->
            shifts.open(otherCashier.principal(),12000,"another-"+UUID.randomUUID(),"test")).status().value());
        assertEquals(403,assertThrows(ApiFailureException.class,()->
            shifts.open(waiter.principal(),0,"waiter-"+UUID.randomUUID(),"test")).status().value());
        assertEquals(403,assertThrows(ApiFailureException.class,()->
            shifts.close(first.id(),waiter.principal(),0,"waiter-close-"+UUID.randomUUID(),
                "Not authorized","test")).status().value());
        assertEquals(403,assertThrows(ApiFailureException.class,()->
            shifts.close(first.id(),otherCashier.principal(),0,"foreign-"+UUID.randomUUID(),
                "Not authorized","test")).status().value());

        UUID table=UUID.randomUUID(),visit=UUID.randomUUID(),guest=UUID.randomUUID();
        UUID order=UUID.randomUUID(),intent=UUID.randomUUID();
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,4)",
            table,"SHIFT-"+table.toString().substring(0,12),"shift-"+table);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,cashier.principal().staffId(),"a".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Private synthetic guest");
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,'SERVED',35000,35000,'SYNTHETIC')
            """,order,visit,guest);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,
                amount_paid_idr,aggregate_status)
            VALUES (?,?,'CASH',35000,35000,'PAID')
            """,intent,order);
        jdbc.update("""
            INSERT INTO cash_tenders(payment_intent_id,staff_user_id,amount_received_idr,
                change_given_idr,idempotency_key)
            VALUES (?,?,50000,15000,?)
            """,intent,cashier.principal().staffId(),"tender-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO receipts(payment_intent_id,order_id,total_idr,document_json)
            VALUES (?,?,35000,'{}'::jsonb)
            """,intent,order);
        var preview=shifts.dashboard().active();
        assertNotNull(preview);
        assertEquals(35000,preview.recordedCashIdr());
        assertEquals(45000,preview.expectedDrawerIdr());
        assertEquals(401,mvc.perform(get("/api/v1/ops/cash-shifts"))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/v1/ops/cash-shifts")
            .header("Authorization","Bearer "+waiter.token())).andReturn().getResponse().getStatus());
        var authorized=mvc.perform(get("/api/v1/ops/cash-shifts")
            .header("Authorization","Bearer "+cashier.token())).andReturn().getResponse();
        assertEquals(200,authorized.getStatus());
        assertTrue(authorized.getHeader("Cache-Control").contains("no-store"));
        assertFalse(authorized.getContentAsString().contains("Private synthetic guest"));

        var closeKey="close-"+first.id();
        var closed=shifts.close(first.id(),cashier.principal(),44000,closeKey,
            "Cash physically counted","close-1");
        assertEquals("CLOSED",closed.state());
        assertEquals(35000,closed.recordedCashIdr());
        assertEquals(45000,closed.expectedDrawerIdr());
        assertEquals(-1000,closed.differenceIdr());
        assertEquals(closed.id(),shifts.close(first.id(),cashier.principal(),44000,closeKey,
            "Cash physically counted","close-replay").id());
        assertEquals(409,assertThrows(ApiFailureException.class,()->
            shifts.close(first.id(),cashier.principal(),44000,"other-"+UUID.randomUUID(),
                "Cash physically counted","test")).status().value());
        assertNull(shifts.dashboard().active());
        assertEquals(1,shifts.dashboard().recent().size());
        assertEquals(2,jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs WHERE resource_type='CASH_DRAWER_SHIFT'
              AND resource_id=?
            """,Integer.class,first.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cash_tenders WHERE payment_intent_id=?",
            Integer.class,intent));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM receipts WHERE order_id=?",
            Integer.class,order));
        assertThrows(DataAccessException.class,()->jdbc.update(
            "UPDATE cash_drawer_shifts SET counted_cash_idr=1 WHERE id=?",first.id()));
        assertThrows(DataAccessException.class,()->jdbc.update(
            "DELETE FROM cash_drawer_shifts WHERE id=?",first.id()));

        var next=shifts.open(otherCashier.principal(),0,"next-"+UUID.randomUUID(),"test-next");
        assertEquals("OPEN",next.state());
        assertEquals("CLOSED",shifts.close(next.id(),manager.principal(),0,
            "manager-"+UUID.randomUUID(),"Supervisor verified empty till","test").state());
    }
}
