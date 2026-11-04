package com.natsx.smartcafe.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

/**
 * True HTTP -> HMAC -> immutable evidence path, with no payable status writes.
 * Uses only synthetic references and an ephemeral database.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments",
    "scs.payments.environment=sandbox",
    "scs.payments.sandbox-digital-checkout-enabled=true"
})
class SandboxSignedWebhookIntegrationTest {
    private static final String KEY=uniqueTestKey();
    private static String uniqueTestKey() {
        byte[] key=new byte[48];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }
    private static final String ENDPOINT="/api/v1/integrations/payments/sandbox/events";
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("scs_signed_fake").withUsername("test").withPassword("test");

    @DynamicPropertySource static void datasource(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);
        p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
        p.add("scs.payments.sandbox-webhook-secret",()->KEY);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(UUID orderId,UUID intentId,String reference) {}

    private Fixture seed() {
        UUID staff=UUID.randomUUID(),table=UUID.randomUUID(),visit=UUID.randomUUID();
        UUID guest=UUID.randomUUID(),order=UUID.randomUUID(),intent=UUID.randomUUID();
        String reference="FAKE_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"webhook-"+staff,"synthetic-invalid-hash");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),"webhook-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,staff,"0".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic Callback");
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,42000,42000,'M3-FAKE-WEBHOOK')
            """,order,visit,guest);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,'QRIS',42000,'PENDING')
            """,intent,order);
        jdbc.update("""
            INSERT INTO digital_payment_attempts(
              payment_intent_id,method,provider_key,provider_reference,
              idempotency_key,amount_idr,state,expires_at)
            VALUES (?,'QRIS','SANDBOX_FAKE',?,?,42000,'PENDING',now()+interval '15 minutes')
            """,intent,reference,"signed_test_"+UUID.randomUUID().toString().replace("-",""));
        return new Fixture(order,intent,reference);
    }

    private String body(String eventId,String ref,long amount,String state) {
        return "{\"eventId\":\""+eventId+"\",\"providerReference\":\""+ref+
            "\",\"method\":\"QRIS\",\"state\":\""+state+
            "\",\"reportedAmountIdr\":"+amount+
            ",\"currency\":\"IDR\",\"environment\":\"SANDBOX_FAKE_NOT_MONEY\"}";
    }
    private String eventId(){return "event_"+UUID.randomUUID().toString().replace("-","");}

    private String signature(String ts,String raw) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((ts+".").getBytes(StandardCharsets.US_ASCII));
        return "sha256="+HexFormat.of().formatHex(
            mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private org.springframework.mock.web.MockHttpServletResponse send(String ts,String sig,
        String raw) throws Exception {
        return mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
            .header("X-Sandbox-Timestamp",ts)
            .header("X-Sandbox-Signature",sig)
            .content(raw)).andReturn().getResponse();
    }

    private void unpaid(Fixture f) {
        assertEquals("PENDING",jdbc.queryForObject("""
            SELECT aggregate_status FROM payment_intents WHERE id=?
            """,String.class,f.intentId()));
        assertEquals(0,jdbc.queryForObject("""
            SELECT amount_paid_idr FROM payment_intents WHERE id=?
            """,Integer.class,f.intentId()));
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM receipts WHERE order_id=?",Integer.class,f.orderId()));
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM kitchen_tickets WHERE order_id=?",Integer.class,f.orderId()));
    }

    @Test void signedSuccessIsStillUntrustedAndExactReplayNeverSettles() throws Exception {
        Fixture f=seed();
        String event=eventId(),ts=Long.toString(Instant.now().getEpochSecond());
        String raw=body(event,f.reference(),42000,"SUCCEEDED");
        String sig=signature(ts,raw);
        var first=send(ts,sig,raw);
        assertEquals(202,first.getStatus(),first.getContentAsString());
        assertTrue(first.getHeader("Cache-Control").contains("no-store"));
        assertTrue(first.getContentAsString().contains("QUARANTINED_NOT_PAYMENT"));
        assertFalse(first.getContentAsString().contains("payloadFingerprint"));
        assertFalse(first.getContentAsString().contains(KEY));
        var second=send(ts,sig,raw);
        assertEquals(202,second.getStatus());
        assertEquals(first.getContentAsString().replaceAll(
            "\"requestId\":\"[^\"]+\"","\"requestId\":\"ID\""),
            second.getContentAsString().replaceAll(
                "\"requestId\":\"[^\"]+\"","\"requestId\":\"ID\""));
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM sandbox_payment_event_inbox WHERE provider_event_id=?",
            Integer.class,event));
        assertEquals("QUARANTINED",jdbc.queryForObject("""
            SELECT classification FROM sandbox_payment_event_inbox WHERE provider_event_id=?
            """,String.class,event));
        unpaid(f);
        var changed=send(ts,signature(ts,body(event,f.reference(),42001,"SUCCEEDED")),
            body(event,f.reference(),42001,"SUCCEEDED"));
        assertEquals(409,changed.getStatus(),"Conflicting signed event cannot replace existing evidence");
        unpaid(f);
    }

    @Test void forgedStaleAndMalformedNotificationsDoNotWriteEvidence() throws Exception {
        Fixture f=seed();
        String ts=Long.toString(Instant.now().getEpochSecond());
        String raw=body(eventId(),f.reference(),42000,"SUCCEEDED");
        assertEquals(401,send(ts,"sha256="+"0".repeat(64),raw).getStatus());
        assertEquals(401,send(ts,signature(ts,raw),raw.replace("SUCCEEDED","FAILED"))
            .getStatus());
        var noHeaders=mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
            .content(raw)).andReturn().getResponse();
        assertEquals(401,noHeaders.getStatus());
        String stale=Long.toString(Instant.now().minusSeconds(1800).getEpochSecond());
        assertEquals(401,send(stale,signature(stale,raw),raw).getStatus());
        String unknown=raw.substring(0,raw.length()-1)+",\"admin\":true}";
        assertEquals(400,send(ts,signature(ts,unknown),unknown).getStatus());
        String invalid=raw.replace("\"SUCCEEDED\"","\"CANCELLED\"");
        assertEquals(400,send(ts,signature(ts,invalid),invalid).getStatus());
        String oversized=" ".repeat(4097);
        assertEquals(400,send(ts,"sha256="+"0".repeat(64),oversized).getStatus());
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM sandbox_payment_event_inbox
            WHERE provider_reference=?
            """,Integer.class,f.reference()));
        unpaid(f);
    }

    @Test void signedMismatchedAmountIsQuarantinedWithoutPaidReceipt() throws Exception {
        Fixture f=seed();
        String ts=Long.toString(Instant.now().getEpochSecond());
        String raw=body(eventId(),f.reference(),42001,"SUCCEEDED");
        var result=send(ts,signature(ts,raw),raw);
        assertEquals(202,result.getStatus(),result.getContentAsString());
        assertTrue(result.getContentAsString().contains("FINANCIAL_MISMATCH"));
        unpaid(f);
    }
}
