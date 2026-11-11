package com.natsx.smartcafe;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Opt-in seed for an EMPTY isolated synthetic MySQL review only.
 * Called solely from the explicitly invoked owner-local runner after
 * Flyway, catalog/TLS and first-ADMIN preflight. Never auto-runs on boot.
 *
 * All SQL must execute on the caller's uncommitted transaction so that
 * account creation + inventory seeding either commit together or roll back.
 */
final class SyntheticReviewCatalogSeed {
    private SyntheticReviewCatalogSeed() {}

    private static final String[][] TABLES = {
        {"71000000-0000-4000-8000-000000000001","P01","portfolio-01","2"},
        {"71000000-0000-4000-8000-000000000002","P02","portfolio-02","4"},
        {"71000000-0000-4000-8000-000000000003","P03","portfolio-03","4"},
        {"71000000-0000-4000-8000-000000000004","P04","portfolio-04","6"},
        {"71000000-0000-4000-8000-000000000005","P05","portfolio-05","2"},
        {"71000000-0000-4000-8000-000000000006","P06","portfolio-06","8"}
    };
    private static final String[][] CATEGORIES = {
        {"72000000-0000-4000-8000-000000000001","Coffee","portfolio-coffee","1"},
        {"72000000-0000-4000-8000-000000000002","Non-Coffee","portfolio-non-coffee","2"},
        {"72000000-0000-4000-8000-000000000003","Bites","portfolio-bites","3"},
        {"72000000-0000-4000-8000-000000000004","Meals","portfolio-meals","4"}
    };
    // Category number, name, description, price in rupiah.
    private static final String[][] ITEMS = {
        {"1","House Espresso","Double-shot espresso with a bold finish.","22000"},
        {"1","Spanish Latte","Espresso and creamy milk with light sweetness.","32000"},
        {"1","Iced Americano","Chilled double-shot coffee with fresh water.","26000"},
        {"1","Caramel Macchiato","Espresso layered with milk and caramel.","35000"},
        {"2","Matcha Latte","Smooth matcha and chilled milk.","34000"},
        {"2","Yuzu Sparkling","Citrus yuzu and sparkling water.","31000"},
        {"2","Chocolate Milk","Rich cocoa with fresh milk.","29000"},
        {"3","Butter Croissant","Golden pastry with a crisp outer layer.","28000"},
        {"3","Truffle Fries","Crisp fries with truffle seasoning.","35000"},
        {"3","Crispy Chicken Bites","Bite-sized chicken with house sauce.","39000"},
        {"4","Chicken Rice Bowl","Seasoned chicken over warm rice.","45000"},
        {"4","Creamy Mushroom Pasta","Pasta with a savory mushroom sauce.","48000"}
    };

    static void verifyTargetsEmpty(Connection connection) throws SQLException {
        for (String table : new String[] {
            "cafe_tables", "table_visits", "visit_guests", "menu_categories",
            "menu_items", "menu_variant_groups", "menu_variant_options",
            "physical_games", "physical_game_copies", "physical_game_loans",
            "orders", "payment_intents"
        }) {
            if (count(connection, table) != 0) {
                throw new IllegalStateException(
                    "Review seed refused: existing business rows in " + table);
            }
        }
    }

    static void seed(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("Synthetic seed requires caller-owned transaction");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)")) {
            for (String[] table : TABLES) {
                ps.setString(1, table[0]);
                ps.setString(2, table[1]);
                ps.setString(3, table[2]);
                ps.setInt(4, Integer.parseInt(table[3]));
                exactlyOne(ps);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO menu_categories(id,name,slug,sort_order) VALUES (?,?,?,?)")) {
            for (String[] category : CATEGORIES) {
                ps.setString(1, category[0]);
                ps.setString(2, category[1]);
                ps.setString(3, category[2]);
                ps.setInt(4, Integer.parseInt(category[3]));
                exactlyOne(ps);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO menu_items(id,category_id,name,description,base_price_idr) VALUES (?,?,?,?,?)")) {
            for (int n = 0; n < ITEMS.length; n++) {
                String[] item = ITEMS[n];
                ps.setString(1, identifier("73000000", n + 1));
                ps.setString(2, identifier("72000000", Integer.parseInt(item[0])));
                ps.setString(3, item[1]);
                ps.setString(4, item[2]);
                ps.setLong(5, Long.parseLong(item[3]));
                exactlyOne(ps);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE item_availability SET available=true,limited_quantity=200 WHERE menu_item_id=?")) {
            for (int n = 1; n <= ITEMS.length; n++) {
                ps.setString(1, identifier("73000000", n));
                exactlyOne(ps);
            }
        }
        try (PreparedStatement group = connection.prepareStatement(
                "INSERT INTO menu_variant_groups(id,menu_item_id,name,required,active) VALUES (?,?,?,?,true)")) {
            group.setString(1, identifier("74000000", 1));
            group.setString(2, identifier("73000000", 2));
            group.setString(3, "Size");
            group.setBoolean(4, true);
            exactlyOne(group);
            group.setString(1, identifier("74000000", 2));
            group.setString(2, identifier("73000000", 2));
            group.setString(3, "Milk");
            group.setBoolean(4, false);
            exactlyOne(group);
        }
        try (PreparedStatement option = connection.prepareStatement(
                "INSERT INTO menu_variant_options(id,group_id,option_key,label,price_delta_idr) VALUES (?,?,?,?,?)")) {
            String[][] options = {
                {"1","1","regular","Regular","0"},
                {"2","1","large","Large","6000"},
                {"3","2","oat","Oat Milk","4000"}
            };
            for (String[] row : options) {
                option.setString(1, identifier("75000000", Integer.parseInt(row[0])));
                option.setString(2, identifier("74000000", Integer.parseInt(row[1])));
                option.setString(3, row[2]);
                option.setString(4, row[3]);
                option.setLong(5, Long.parseLong(row[4]));
                exactlyOne(option);
            }
        }
        try (PreparedStatement game = connection.prepareStatement(
                "INSERT INTO physical_games(id,name,description) VALUES (?,?,?)")) {
            String[][] games = {
                {"Color Card Deck","Synthetic card game for cafe loan workflow"},
                {"Classic Checkers","Synthetic board-game inventory for review"}
            };
            for (int n = 0; n < games.length; n++) {
                game.setString(1, identifier("76000000", n + 1));
                game.setString(2, games[n][0]);
                game.setString(3, games[n][1]);
                exactlyOne(game);
            }
        }
        try (PreparedStatement copy = connection.prepareStatement(
                "INSERT INTO physical_game_copies(id,game_id,copy_code) VALUES (?,?,?)")) {
            String[][] copies = {
                {"1","1","CARD-DEMO-001"},
                {"2","1","CARD-DEMO-002"},
                {"3","2","CHECK-DEMO-001"}
            };
            for (String[] row : copies) {
                copy.setString(1, identifier("77000000", Integer.parseInt(row[0])));
                copy.setString(2, identifier("76000000", Integer.parseInt(row[1])));
                copy.setString(3, row[2]);
                exactlyOne(copy);
            }
        }
        verifySeedCounts(connection);
    }

    static void verifySeedCounts(Connection connection) throws SQLException {
        String[] tables = {
            "cafe_tables", "menu_categories", "menu_items", "item_availability",
            "menu_variant_groups", "menu_variant_options", "physical_games",
            "physical_game_copies"
        };
        int[] expected = {6,4,12,12,2,3,2,3};
        for (int i = 0; i < tables.length; i++) {
            if (count(connection, tables[i]) != expected[i]) {
                throw new IllegalStateException(
                    "Unexpected synthetic inventory count in " + tables[i]);
            }
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        // Only fixed, source-controlled table names are passed. Never user SQL.
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("Missing count result");
            return rs.getLong(1);
        }
    }

    private static String identifier(String prefix, int suffix) {
        return prefix + "-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", suffix);
    }

    private static void exactlyOne(PreparedStatement statement) throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new IllegalStateException("Synthetic database write affected unexpected row count");
        }
    }
}
