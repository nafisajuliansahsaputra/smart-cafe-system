package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Explicit owner-local, one-time setup. Never picked up by ordinary Maven tests.
 * Writes only to fully migrated, otherwise EMPTY scs_review_natsx. Never runs
 * Flyway clean/repair/migrate, changes production or enables merchant payments.
 */
final class HostedAivenReviewOnboarding {
    private static final String HOST =
        "mysql-1e5b1eb2-nafisajuliansahsaputra-811e.b.aivencloud.com";
    private static final String SCHEMA = "scs_review_natsx";
    private static final Set<String> ROLES =
        Set.of("ADMIN", "MANAGER", "WAITER", "KITCHEN", "CASHIER");

    @Test void oneTimeEmptyReviewSetup() throws Exception {
        if (!"INITIALIZE_SYNTHETIC_REVIEW".equals(System.getenv("SCS_ONBOARD_ACK"))
            || !HOST.equals(required("SCS_ONBOARD_HOST"))
            || !"12886".equals(required("SCS_ONBOARD_PORT"))) {
            throw new IllegalStateException("Wrong target or missing explicit owner consent");
        }
        String login = required("SCS_ONBOARD_LOGIN");
        String password = required("SCS_ONBOARD_ADMIN_PASSWORD");
        if (!login.matches("[A-Za-z][A-Za-z0-9._-]{2,39}")
            || password.length() < 24 || password.length() > 72
            || !password.chars().allMatch(c -> c >= 33 && c <= 126)) {
            throw new IllegalStateException("Invalid review admin credential policy");
        }
        Path store = Files.createTempFile("scs-review-onboard-", ".p12");
        byte[] nonce = new byte[24];
        new SecureRandom().nextBytes(nonce);
        String trustPassword = HexFormat.of().formatHex(nonce);
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, trustPassword.toCharArray());
            try (InputStream input = Files.newInputStream(
                    Path.of(required("SCS_ONBOARD_CA")).toRealPath())) {
                X509Certificate cert = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(input);
                cert.checkValidity();
                ks.setCertificateEntry("aiven-review-ca", cert);
            }
            try (OutputStream output = Files.newOutputStream(store)) {
                ks.store(output, trustPassword.toCharArray());
            }
            Properties tls = new Properties();
            tls.setProperty("sslMode", "VERIFY_IDENTITY");
            tls.setProperty("trustCertificateKeyStoreUrl", store.toUri().toString());
            tls.setProperty("trustCertificateKeyStoreType", "PKCS12");
            tls.setProperty("trustCertificateKeyStorePassword", trustPassword);
            tls.setProperty("fallbackToSystemTrustStore", "false");
            tls.setProperty("allowPublicKeyRetrieval", "false");
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            ds.setUrl("jdbc:mysql://" + HOST + ":12886/" + SCHEMA);
            ds.setUsername(required("SCS_ONBOARD_DB_USERNAME"));
            ds.setPassword(required("SCS_ONBOARD_DB_PASSWORD"));
            ds.setConnectionProperties(tls);

            Flyway.configure().dataSource(ds).locations("classpath:db/mysql")
                .load().validate();
            try (Connection con = ds.getConnection()) {
                assertEquals(SCHEMA, con.getCatalog(), "Unexpected DB");
                try (Statement statement = con.createStatement();
                     ResultSet ssl = statement.executeQuery(
                         "SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                    assertTrue(ssl.next() && !ssl.getString(2).isBlank(),
                        "Verified TLS required");
                }
                assertEquals(31, count(con,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"));
                assertEquals(0, count(con,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0"));
                List<String> roles = strings(con, "SELECT role_key FROM staff_roles");
                assertEquals(ROLES, Set.copyOf(roles));
                assertEquals(5, roles.size());
                con.setAutoCommit(false);
                try {
                    assertEquals(List.of("ADMIN"), strings(con,
                        "SELECT role_key FROM staff_roles WHERE role_key='ADMIN' FOR UPDATE"));
                    List<String> tables = strings(con, """
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND table_type='BASE TABLE'
                          AND table_name NOT IN ('staff_roles','flyway_schema_history')
                        ORDER BY table_name
                        """);
                    assertTrue(tables.size() >= 40, "Incomplete schema");
                    assertTrue(tables.containsAll(List.of(
                        "staff_users","cafe_tables","menu_categories","menu_items",
                        "orders","payment_intents","receipts","audit_logs")));
                    for (String table : tables) {
                        if (!table.matches("[a-z][a-z0-9_]*")) {
                            throw new IllegalStateException("Unexpected SQL identifier");
                        }
                        if (count(con, "SELECT COUNT(*) FROM " + table) != 0) {
                            throw new IllegalStateException(
                                "Business records already exist; refusing initialization");
                        }
                    }
                    String adminId = UUID.randomUUID().toString();
                    String hash = new BCryptPasswordEncoder(12).encode(password);
                    insert(con, "INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
                        adminId, login.toLowerCase(Locale.ROOT), hash);
                    insert(con, """
                        INSERT INTO staff_role_assignments(staff_user_id,role_key)
                        VALUES (?,'ADMIN')
                        """, adminId);
                    for (int n=1; n<=5; n++) {
                        String number = "%02d".formatted(n);
                        insert(con, """
                            INSERT INTO cafe_tables(id,table_code,public_alias,capacity,status)
                            VALUES (?,?,?,?,'AVAILABLE')
                            """, UUID.randomUUID().toString(),
                                "T"+number, "meja-"+number, 4);
                    }
                    String coffee=UUID.randomUUID().toString();
                    String tea=UUID.randomUUID().toString();
                    String pastry=UUID.randomUUID().toString();
                    category(con,coffee,"Kopi","kopi",10);
                    category(con,tea,"Teh & Matcha","teh-matcha",20);
                    category(con,pastry,"Pastry","pastry",30);
                    item(con,coffee,"Iced Oat Latte",32000,"Espresso, oat milk, es.");
                    item(con,coffee,"Caramel Latte",34000,"Espresso dengan karamel.");
                    item(con,coffee,"Espresso",23000,"Double shot espresso.");
                    item(con,tea,"Matcha Latte",33000,"Matcha dan susu segar.");
                    item(con,tea,"Lemon Tea",22000,"Teh lemon dingin.");
                    item(con,pastry,"Butter Croissant",24000,"Croissant mentega.");
                    item(con,pastry,"Chocolate Croissant",29000,"Croissant cokelat.");
                    assertEquals(1,count(con,"SELECT COUNT(*) FROM staff_users"));
                    assertEquals(1,count(con,
                        "SELECT COUNT(*) FROM staff_role_assignments WHERE role_key='ADMIN'"));
                    assertEquals(5,count(con,"SELECT COUNT(*) FROM cafe_tables"));
                    assertEquals(3,count(con,"SELECT COUNT(*) FROM menu_categories"));
                    assertEquals(7,count(con,"SELECT COUNT(*) FROM menu_items"));
                    con.commit();
                    System.out.println("PASS: synthetic review administrator and 5 tables, "
                        + "3 categories, 7 items created. Real payments remain disabled.");
                } catch (Exception | Error ex) {
                    con.rollback();
                    throw ex;
                }
            }
        } finally {
            Files.deleteIfExists(store);
        }
    }
    private static void category(Connection c,String id,String name,String slug,int sort)
        throws SQLException {
        insert(c,"INSERT INTO menu_categories(id,name,slug,sort_order,active) "
            +"VALUES (?,?,?,?,true)",id,name,slug,sort);
    }
    private static void item(Connection c,String category,String name,long price,String desc)
        throws SQLException {
        insert(c,"INSERT INTO menu_items "
            +"(id,category_id,name,description,base_price_idr,availability) "
            +"VALUES (?,?,?,?,?,'AVAILABLE')",
            UUID.randomUUID().toString(),category,name,desc,price);
    }
    private static void insert(Connection c,String sql,Object... args) throws SQLException {
        try (PreparedStatement p=c.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) p.setObject(i+1,args[i]);
            assertEquals(1,p.executeUpdate());
        }
    }
    private static int count(Connection c,String sql) throws SQLException {
        try (Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getInt(1);
        }
    }
    private static List<String> strings(Connection c,String sql) throws SQLException {
        try (Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)) {
            List<String> result = new ArrayList<>();
            while(r.next()) result.add(r.getString(1));
            return result;
        }
    }
    private static String required(String key) {
        String val=System.getenv(key);
        if (val==null||val.isBlank())
            throw new IllegalStateException("Missing owner-local setup variable: "+key);
        return val;
    }
}
