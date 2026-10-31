package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.natsx.smartcafe.identity.SecureToken;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Sandbox environment alone must NOT expose working fake-payment endpoints. */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments",
    "scs.payments.environment=sandbox",
    "scs.payments.sandbox-digital-checkout-enabled=false"
})
class SandboxAttemptOptInDenialIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("scs_sandbox_disabled").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",postgres::getJdbcUrl);
        props.add("spring.datasource.username",postgres::getUsername);
        props.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void unenabledSandboxEndpointsFailClosedForAuthenticatedOwner() throws Exception {
        UUID staff=UUID.randomUUID(),table=UUID.randomUUID(),
            visit=UUID.randomUUID(),guest=UUID.randomUUID();
        String bearer=SecureToken.generate();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"sandbox-denial-"+staff,"unusable-test-hash");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),"sandbox-denial-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,staff,"0".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic Optin Denial");
        jdbc.update("""
            INSERT INTO guest_sessions(token_hash,guest_id,visit_id,expires_at)
            VALUES (?,?,?,now()+interval '1 hour')
            """,SecureToken.sha256(bearer),guest,visit);
        String url="/api/v1/me/orders/"+UUID.randomUUID()+"/sandbox-attempts";
        assertEquals(403,mvc.perform(get(url)
            .header("Authorization","Bearer "+bearer))
            .andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post(url)
            .header("Authorization","Bearer "+bearer)
            .header("Idempotency-Key","synthetic-denial-key")
            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"QRIS\"}"))
            .andReturn().getResponse().getStatus());
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts
            """,Integer.class));
    }
}
