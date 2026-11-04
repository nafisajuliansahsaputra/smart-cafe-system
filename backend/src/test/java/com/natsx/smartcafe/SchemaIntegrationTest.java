package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class SchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void flywayCreatesSchemaAndOnlyOneActiveVisitPerTable() {
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM staff_roles", Integer.class));
        UUID staff = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("INSERT INTO staff_users(id, login, password_hash) VALUES (?,?,?)",
            staff, staff.toString(), "synthetic-test-hash");
        jdbc.update("INSERT INTO cafe_tables(id, table_code, public_alias, capacity) VALUES (?,?,?,?)",
            table, table.toString(), "test-" + table, 4);
        jdbc.update("INSERT INTO table_visits(table_id,opened_by,join_secret_hash) VALUES (?,?,?)",
            table, staff, "a".repeat(64));
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO table_visits(table_id,opened_by,join_secret_hash) VALUES (?,?,?)",
                table, staff, "b".repeat(64)));
    }


    @Test void digitalAttemptSchemaRejectsCashMismatchesAndDuplicatePending() {
        UUID staff=UUID.randomUUID(),table=UUID.randomUUID(),visit=UUID.randomUUID();
        UUID guest=UUID.randomUUID(),order=UUID.randomUUID(),intent=UUID.randomUUID();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"m3-"+staff,"synthetic-hash-only");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),"m3-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,staff,"a".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic Guest");
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,25000,25000,'M3-SCHEMA-ONLY')
            """,order,visit,guest);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,'QRIS',25000,'PENDING')
            """,intent,order);

        var reference="FAKE_"+UUID.randomUUID().toString().replace("-","");
        var key="fake_schema_"+UUID.randomUUID().toString().replace("-","");
        assertEquals(1,jdbc.update("""
            INSERT INTO digital_payment_attempts(
                payment_intent_id,method,provider_key,provider_reference,idempotency_key,
                amount_idr,expires_at)
            VALUES (?,'QRIS','SANDBOX_FAKE',?,?,25000,now()+interval '15 minutes')
            """,intent,reference,key));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            INSERT INTO digital_payment_attempts(
                payment_intent_id,method,provider_key,provider_reference,idempotency_key,
                amount_idr,expires_at)
            VALUES (?,'QRIS','SANDBOX_FAKE',?,?,25000,now()+interval '15 minutes')
            """,intent,reference,key));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            INSERT INTO digital_payment_attempts(
                payment_intent_id,method,provider_key,provider_reference,idempotency_key,
                amount_idr,expires_at)
            VALUES (?,'VA','SANDBOX_FAKE',?,?,25000,now()+interval '15 minutes')
            """,intent,"FAKE_"+UUID.randomUUID().toString().replace("-",""),
            "wrongmethod_"+UUID.randomUUID().toString().replace("-","")));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            INSERT INTO digital_payment_attempts(
                payment_intent_id,method,provider_key,provider_reference,idempotency_key,
                amount_idr,expires_at)
            VALUES (?,'QRIS','SANDBOX_FAKE',?,?,25001,now()+interval '15 minutes')
            """,intent,"FAKE_"+UUID.randomUUID().toString().replace("-",""),
            "wrongamount_"+UUID.randomUUID().toString().replace("-","")));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            INSERT INTO digital_payment_attempts(
                payment_intent_id,method,provider_key,provider_reference,idempotency_key,
                amount_idr,expires_at)
            VALUES (?,'QRIS','SANDBOX_FAKE',?,?,25000,now()+interval '15 minutes')
            """,intent,"FAKE_"+UUID.randomUUID().toString().replace("-",""),
            "secondactive_"+UUID.randomUUID().toString().replace("-","")));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            UPDATE digital_payment_attempts SET amount_idr=26000
            WHERE payment_intent_id=?
            """,intent));
        assertEquals(0,jdbc.queryForObject("""
            SELECT amount_paid_idr FROM payment_intents WHERE id=?
            """,Integer.class,intent));
        assertEquals("PENDING",jdbc.queryForObject("""
            SELECT aggregate_status FROM payment_intents WHERE id=?
            """,String.class,intent));

        jdbc.update("""
            UPDATE digital_payment_attempts SET state='EXPIRED',updated_at=now()
            WHERE payment_intent_id=?
            """,intent);
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            UPDATE digital_payment_attempts SET state='PENDING',updated_at=now()
            WHERE payment_intent_id=?
            """,intent));
        assertEquals("EXPIRED",jdbc.queryForObject("""
            SELECT state FROM digital_payment_attempts WHERE payment_intent_id=?
            """,String.class,intent));
        assertEquals(1,jdbc.update("""
            INSERT INTO digital_payment_attempts(
                payment_intent_id,method,provider_key,provider_reference,idempotency_key,
                amount_idr,expires_at)
            VALUES (?,'QRIS','SANDBOX_FAKE',?,?,25000,now()+interval '15 minutes')
            """,intent,"FAKE_"+UUID.randomUUID().toString().replace("-",""),
            "retry_valid_"+UUID.randomUUID().toString().replace("-","")));
    }

    @Test void fakeDigitalAttemptCannotAttachToCashIntent() {
        UUID staff=UUID.randomUUID(),table=UUID.randomUUID(),visit=UUID.randomUUID();
        UUID guest=UUID.randomUUID(),order=UUID.randomUUID(),intent=UUID.randomUUID();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"m3cash-"+staff,"synthetic-hash-only");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),"m3cash-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,staff,"b".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic Cash Guest");
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,12000,12000,'M3-SCHEMA-ONLY')
            """,order,visit,guest);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,'CASH',12000,'AWAITING_CASH')
            """,intent,order);
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            INSERT INTO digital_payment_attempts(
                payment_intent_id,method,provider_key,provider_reference,idempotency_key,
                amount_idr,expires_at)
            VALUES (?,'QRIS','SANDBOX_FAKE',?,?,12000,now()+interval '15 minutes')
            """,intent,"FAKE_"+UUID.randomUUID().toString().replace("-",""),
            "cash_reject_"+UUID.randomUUID().toString().replace("-","")));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts WHERE payment_intent_id=?
            """,Integer.class,intent));
    }

    @Test void publicDatabaseProbeWorksAndStaffApiIsDenied() throws Exception {
        var healthy = mvc.perform(get("/api/v1/system/status")).andReturn().getResponse();
        assertEquals(200, healthy.getStatus());
        assertTrue(healthy.getContentAsString().contains("\"connected\""));

        var denied = mvc.perform(get("/api/v1/ops/orders")).andReturn().getResponse();
        assertTrue(denied.getStatus() == 401 || denied.getStatus() == 403);
        assertTrue(denied.getContentAsString().contains("\"UNAUTHENTICATED\"")
            || denied.getContentAsString().contains("\"FORBIDDEN\""));
    }

    @Test void rejectsNegativeMenuPrices() {
        UUID cat = UUID.randomUUID();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            cat, "Test", "category-" + cat);
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO menu_items(category_id,name,base_price_idr) VALUES (?,?,?)",
                cat, "Invalid", -1));
    }
}
