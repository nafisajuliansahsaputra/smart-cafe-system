package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
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

    @Test void rejectsNegativeMenuPrices() {
        UUID cat = UUID.randomUUID();
        jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
            cat, "Test", "category-" + cat);
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO menu_items(category_id,name,base_price_idr) VALUES (?,?,?)",
                cat, "Invalid", -1));
    }
}
