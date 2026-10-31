package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Upgrade rehearsal against an actual V27 PostgreSQL database, not a new V30 schema.
 * All customer/staff/payment data is synthetic and lives in a disposable Testcontainer.
 */
@Testcontainers
class LegacyUpgradeToV30IntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_upgrade_v27_ci")
        .withUsername("rehearsal")
        .withPassword("synthetic-testcontainers-only");

    private static final UUID MANAGER =
        UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final UUID WAITER =
        UUID.fromString("f0000000-0000-4000-8000-000000000002");
    private static final UUID TABLE =
        UUID.fromString("f0000000-0000-4000-8000-000000000003");
    private static final UUID VISIT =
        UUID.fromString("f0000000-0000-4000-8000-000000000004");
    private static final UUID GUEST =
        UUID.fromString("f0000000-0000-4000-8000-000000000005");
    private static final UUID CATEGORY =
        UUID.fromString("f0000000-0000-4000-8000-000000000006");
    private static final UUID ITEM =
        UUID.fromString("f0000000-0000-4000-8000-000000000007");
    private static final UUID ORDER =
        UUID.fromString("f0000000-0000-4000-8000-000000000008");
    private static final UUID LINE =
        UUID.fromString("f0000000-0000-4000-8000-000000000009");
    private static final UUID PAYMENT =
        UUID.fromString("f0000000-0000-4000-8000-000000000010");
    private static final UUID TENDER =
        UUID.fromString("f0000000-0000-4000-8000-000000000011");
    private static final UUID RECEIPT =
        UUID.fromString("f0000000-0000-4000-8000-000000000012");
    private static final UUID GAME =
        UUID.fromString("f0000000-0000-4000-8000-000000000013");
    private static final UUID COPY =
        UUID.fromString("f0000000-0000-4000-8000-000000000014");
    private static final UUID LOAN =
        UUID.fromString("f0000000-0000-4000-8000-000000000015");
    private static final UUID AUDIT =
        UUID.fromString("f0000000-0000-4000-8000-000000000016");

    @Test
    void existingV27MoneyReceiptsGamesAndAuditsSurviveV30Migration() {
        // This database starts completely empty; no app context, migrations or
        // production credentials can be inherited from other integration tests.
        DataSource data = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(data);
        Flyway old = Flyway.configure().dataSource(data)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("27"))
            .load();
        old.migrate();
        assertEquals(27, jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class));
        assertFalse(columnExists(jdbc,"cash_tenders","cash_shift_id"));
        assertFalse(tableExists(jdbc,"cash_drawer_shifts"));
        assertFalse(tableExists(jdbc,"cash_variance_review_actions"));

        seedLegacyFixture(jdbc);
        Map<String,List<Map<String,Object>>> before = legacySnapshot(jdbc);
        assertEquals(1, before.get("receipt").size());
        assertEquals(1, before.get("tender").size());
        assertEquals(1, before.get("loan").size());
        assertEquals(1, before.get("audit").size());

        // The same database and physical data are upgraded in place, with no
        // schema reset, truncate, import, or replay of fixture SQL.
        Flyway upgraded = Flyway.configure().dataSource(data)
            .locations("classpath:db/migration").load();
        upgraded.migrate();
        upgraded.validate();
        assertEquals(30,jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class));
        assertEquals(3,jdbc.queryForObject("""
            SELECT count(*) FROM flyway_schema_history
            WHERE success AND version IN ('28','29','30')
            """,Integer.class));
        assertEquals(before,legacySnapshot(jdbc),
            "Existing V27 payment, immutable receipt, game, order and audit evidence changed");
        assertTrue(columnExists(jdbc,"cash_tenders","cash_shift_id"));
        assertTrue(tableExists(jdbc,"cash_drawer_shifts"));
        assertTrue(tableExists(jdbc,"cash_tender_handoffs"));
        assertTrue(tableExists(jdbc,"cash_variance_review_actions"));
        assertNull(jdbc.queryForObject(
            "SELECT cash_shift_id FROM cash_tenders WHERE id=?",
            UUID.class,TENDER),"Pre-shift money must never be backfilled to a drawer");
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM cash_drawer_shifts",Integer.class));

        // Existing historical paid receipts remain immutable after the upgrade.
        assertThrows(DataAccessException.class,()->jdbc.update(
            "UPDATE receipts SET total_idr=1 WHERE id=?",RECEIPT));
        assertThrows(DataAccessException.class,()->jdbc.update(
            "DELETE FROM receipts WHERE id=?",RECEIPT));

        // New shift and post-upgrade cash tender can be written alongside
        // unchanged historical data with explicit ownership.
        UUID shift=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,10000,'upgrade-rehearsal-open')
            """,shift,MANAGER);
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,0,'second-concurrent-open')
            """,UUID.randomUUID(),MANAGER));

        UUID order2=UUID.randomUUID(),pay2=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,
                pricing_policy_version)
            VALUES (?,?,?,'SERVED',15000,15000,'SYNTHETIC-UPGRADE')
            """,order2,VISIT,GUEST);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,
                amount_due_idr,amount_paid_idr,aggregate_status)
            VALUES (?,?,'CASH',15000,15000,'PAID')
            """,pay2,order2);
        UUID tender2=jdbc.queryForObject("""
            INSERT INTO cash_tenders(payment_intent_id,staff_user_id,
                amount_received_idr,change_given_idr,idempotency_key,cash_shift_id)
            VALUES (?,?,20000,5000,'upgrade-new-cash-tender',?) RETURNING id
            """,UUID.class,pay2,WAITER,shift);
        assertNotNull(tender2);
        // A forged custody record on a legacy tender is rejected by the V29 trigger.
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO cash_tender_handoffs(tender_id,cash_shift_id,waiter_staff_id)
            VALUES (?,?,?)
            """,TENDER,shift,WAITER));
        jdbc.update("""
            INSERT INTO cash_tender_handoffs(tender_id,cash_shift_id,waiter_staff_id)
            VALUES (?,?,?)
            """,tender2,shift,WAITER);
        jdbc.update("""
            UPDATE cash_tender_handoffs
            SET received_by=?, received_at=clock_timestamp(),
                acknowledgment_key='upgrade-handoff-confirm'
            WHERE tender_id=?
            """,MANAGER,tender2);
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE cash_tender_handoffs SET received_by=? WHERE tender_id=?
            """,WAITER,tender2));

        jdbc.update("""
            UPDATE cash_drawer_shifts
            SET closed_at=clock_timestamp(),closed_by=?,
                close_key='upgrade-rehearsal-close',recorded_cash_idr=15000,
                expected_drawer_idr=25000,counted_cash_idr=24000,
                difference_idr=-1000,closing_reason='Counted after synthetic handoff'
            WHERE id=? AND closed_at IS NULL
            """,MANAGER,shift);
        assertEquals(-1000L,jdbc.queryForObject(
            "SELECT difference_idr FROM cash_drawer_shifts WHERE id=?",
            Long.class,shift));
        jdbc.update("""
            INSERT INTO cash_variance_review_actions
                (shift_id,actor_staff_id,action_code,note,idempotency_key)
            VALUES (?,?,'REQUEST_RECOUNT',
                'Synthetic post-upgrade physical recount review',
                'upgrade-recount-action')
            """,shift,MANAGER);
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM cash_variance_review_actions WHERE shift_id=?
            """,Integer.class,shift));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM cash_variance_review_actions WHERE shift_id=?
            """,shift));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE cash_drawer_shifts SET difference_idr=0 WHERE id=?
            """,shift));
        assertEquals(before,legacySnapshot(jdbc),
            "Post-upgrade operations must not rewrite pre-existing evidence");
        // An already current database is idempotent on repeat deployment.
        assertEquals(0,upgraded.migrate().migrationsExecuted);
    }

    private static boolean tableExists(JdbcTemplate jdbc,String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT to_regclass('public.' || ?) IS NOT NULL",Boolean.class,table));
    }
    private static boolean columnExists(JdbcTemplate jdbc,String table,String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM information_schema.columns
            WHERE table_schema='public' AND table_name=? AND column_name=?)
            """,Boolean.class,table,column));
    }

    private static void seedLegacyFixture(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO staff_users(id,login,password_hash) VALUES
            (?,'upgrade-manager','synthetic-not-a-login-hash'),
            (?,'upgrade-waiter','synthetic-not-a-login-hash')
            """,MANAGER,WAITER);
        jdbc.update("""
            INSERT INTO staff_role_assignments(staff_user_id,role_key)
            VALUES (?,'MANAGER'),(?,'WAITER')
            """,MANAGER,WAITER);
        jdbc.update("""
            INSERT INTO cafe_tables(id,table_code,public_alias,capacity)
            VALUES (?,'UPGRADE-27','synthetic-upgrade-27',4)
            """,TABLE);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,VISIT,TABLE,WAITER,"a".repeat(64));
        jdbc.update("""
            INSERT INTO visit_guests(id,visit_id,display_name)
            VALUES (?,?,'Synthetic Upgrade Guest')
            """,GUEST,VISIT);
        jdbc.update("""
            INSERT INTO menu_categories(id,name,slug)
            VALUES (?,'Upgrade Test','upgrade-test')
            """,CATEGORY);
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr)
            VALUES (?,?,'Synthetic Legacy Drink',35000)
            """,ITEM,CATEGORY);
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,
                pricing_policy_version)
            VALUES (?,?,?,'SERVED',35000,35000,'SYNTHETIC-V27')
            """,ORDER,VISIT,GUEST);
        jdbc.update("""
            INSERT INTO order_lines(id,order_id,menu_item_id,name_snapshot,
                unit_price_idr,quantity,line_total_idr)
            VALUES (?,?,?,'Synthetic Legacy Drink',35000,1,35000)
            """,LINE,ORDER,ITEM);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,
                amount_due_idr,amount_paid_idr,aggregate_status)
            VALUES (?,?,'CASH',35000,35000,'PAID')
            """,PAYMENT,ORDER);
        jdbc.update("""
            INSERT INTO cash_tenders(id,payment_intent_id,staff_user_id,
                amount_received_idr,change_given_idr,idempotency_key)
            VALUES (?,?,?,50000,15000,'upgrade-legacy-tender')
            """,TENDER,PAYMENT,WAITER);
        jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            VALUES (?,?,?,35000,'{"test":"upgrade-v27"}'::jsonb)
            """,RECEIPT,PAYMENT,ORDER);
        jdbc.update("""
            INSERT INTO physical_games(id,name)
            VALUES (?,'Synthetic Legacy Game')
            """,GAME);
        jdbc.update("""
            INSERT INTO physical_game_copies(id,game_id,copy_code)
            VALUES (?,?,'UPGRADE-LEGACY-COPY')
            """,COPY,GAME);
        jdbc.update("""
            INSERT INTO physical_game_loans(id,visit_id,guest_id,game_id,copy_id,
                status,assigned_at,handed_over_at,returned_at,assigned_by,
                handed_over_by,returned_by)
            VALUES (?,?,?, ?,?,'RETURNED',now(),now(),now(),?,?,?)
            """,LOAN,VISIT,GUEST,GAME,COPY,WAITER,WAITER,WAITER);
        jdbc.update("""
            INSERT INTO audit_logs(id,actor_type,actor_id,operation,
                resource_type,resource_id,correlation_id)
            VALUES (?,'STAFF',?,'UPGRADE_SYNTHETIC_LEGACY','PAYMENT_INTENT',
                ?,'synthetic-upgrade-v27')
            """,AUDIT,WAITER,PAYMENT);
    }

    private static Map<String,List<Map<String,Object>>> legacySnapshot(JdbcTemplate jdbc) {
        Map<String,List<Map<String,Object>>> result = new LinkedHashMap<>();
        result.put("staff",jdbc.queryForList(
            "SELECT id,login,password_hash,enabled FROM staff_users WHERE id IN (?,?) ORDER BY id",
            MANAGER,WAITER));
        result.put("roles",jdbc.queryForList(
            "SELECT staff_user_id,role_key FROM staff_role_assignments WHERE staff_user_id IN (?,?) ORDER BY staff_user_id",
            MANAGER,WAITER));
        result.put("visit",jdbc.queryForList(
            "SELECT id,table_id,opened_by,status,join_secret_hash FROM table_visits WHERE id=?",VISIT));
        result.put("guest",jdbc.queryForList(
            "SELECT id,visit_id,display_name,phone_last4,receipt_opt_in_at FROM visit_guests WHERE id=?",GUEST));
        result.put("menu",jdbc.queryForList(
            "SELECT id,category_id,name,base_price_idr,revision FROM menu_items WHERE id=?",ITEM));
        result.put("order",jdbc.queryForList(
            "SELECT id,visit_id,guest_id,status,subtotal_idr,total_idr,pricing_policy_version FROM orders WHERE id=?",ORDER));
        result.put("line",jdbc.queryForList(
            "SELECT id,order_id,menu_item_id,name_snapshot,unit_price_idr,quantity,line_total_idr FROM order_lines WHERE id=?",LINE));
        result.put("payment",jdbc.queryForList(
            "SELECT id,order_id,method_selected,amount_due_idr,amount_paid_idr,aggregate_status FROM payment_intents WHERE id=?",PAYMENT));
        result.put("tender",jdbc.queryForList(
            "SELECT id,payment_intent_id,staff_user_id,amount_received_idr,change_given_idr,idempotency_key,recorded_at FROM cash_tenders WHERE id=?",TENDER));
        result.put("receipt",jdbc.queryForList(
            "SELECT id,payment_intent_id,order_id,receipt_number,total_idr,currency,issued_at,document_json FROM receipts WHERE id=?",RECEIPT));
        result.put("game",jdbc.queryForList(
            "SELECT id,name,description,active FROM physical_games WHERE id=?",GAME));
        result.put("copy",jdbc.queryForList(
            "SELECT id,game_id,copy_code,condition FROM physical_game_copies WHERE id=?",COPY));
        result.put("loan",jdbc.queryForList(
            "SELECT id,visit_id,guest_id,copy_id,status,returned_at FROM physical_game_loans WHERE id=?",LOAN));
        result.put("audit",jdbc.queryForList(
            "SELECT id,actor_type,actor_id,operation,resource_type,resource_id,correlation_id,occurred_at FROM audit_logs WHERE id=?",AUDIT));
        return result;
    }
}
