package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.natsx.smartcafe.ops.CashReceiptLedgerService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class CashReceiptLedgerIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_receipt_page").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CashReceiptLedgerService ledger;

    private void paidReceipt(UUID visit, UUID guest, int amount, Instant issuedAt) {
        UUID order = UUID.randomUUID(), intent = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,'SERVED',?,?,'SYNTHETIC-LEDGER')
            """, order, visit, guest, amount, amount);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,
                amount_paid_idr,aggregate_status)
            VALUES (?,?,'CASH',?,?,'PAID')
            """, intent, order, amount, amount);
        jdbc.update("""
            INSERT INTO receipts(payment_intent_id,order_id,total_idr,document_json,issued_at)
            VALUES (?,?,?,'{}'::jsonb,?)
            """, intent, order, amount, Timestamp.from(issuedAt));
    }

    @Test void pagesTwentyReceiptsInWibDayWithoutMixingAdjacentDays() {
        UUID staff = UUID.randomUUID(), table = UUID.randomUUID();
        UUID visit = UUID.randomUUID(), guest = UUID.randomUUID();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff, "ledger-" + staff, "synthetic-hash");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table, "L-" + table, "ledger-" + table, 4);
        jdbc.update("INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash) VALUES (?,?,?,?)",
            visit, table, staff, "c".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest, visit, "Private Guest Never Displayed");

        Instant start = Instant.parse("2026-10-31T01:00:00Z");
        for (int i = 0; i < 22; i++) {
            paidReceipt(visit, guest, 10000 + i, start.plusSeconds(i * 60L));
        }
        // Boundary at 00:00 WIB = previous day's 17:00 UTC.
        paidReceipt(visit, guest, 11000, Instant.parse("2026-10-30T16:59:59Z"));
        paidReceipt(visit, guest, 12000, Instant.parse("2026-10-31T17:00:00Z"));

        var first = ledger.byDay("2026-10-31", "0");
        var second = ledger.byDay("2026-10-31", "1");
        var third = ledger.byDay("2026-10-31", "2");

        assertEquals("Asia/Jakarta", first.timezone());
        assertEquals(20, first.items().size());
        assertTrue(first.hasNext());
        assertEquals(2, second.items().size());
        assertFalse(second.hasNext());
        assertEquals(0, third.items().size());
        assertFalse(third.hasNext());
        assertEquals(10021, first.items().getFirst().totalIdr());
        assertEquals(10002, first.items().getLast().totalIdr());
        assertEquals(10001, second.items().getFirst().totalIdr());
        assertEquals(10000, second.items().getLast().totalIdr());
        assertEquals(22, first.items().size() + second.items().size());
        assertEquals(1, ledger.byDay("2026-10-30", "0").items().size());
        assertEquals(1, ledger.byDay("2026-11-01", "0").items().size());
    }
}
