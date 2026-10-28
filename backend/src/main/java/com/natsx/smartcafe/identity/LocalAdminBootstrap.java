package com.natsx.smartcafe.identity;

import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time LOCAL DEVELOPMENT bootstrap only. Never enabled in normal startup.
 * No hard-coded credentials; an existing account always blocks the bootstrap.
 */
@Component
@Profile("dev-bootstrap")
public class LocalAdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public LocalAdminBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc=jdbc;this.encoder=encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String login=System.getenv("SCS_BOOTSTRAP_USERNAME");
        String secret=System.getenv("SCS_BOOTSTRAP_PASSWORD");
        if(login==null || !login.matches("[a-zA-Z0-9._-]{3,120}")
            || secret==null || secret.length()<16 || secret.length()>128) {
            throw new IllegalStateException("Local bootstrap requires a valid login and 16+ character password");
        }
        Long existing=jdbc.queryForObject("SELECT count(*) FROM staff_users",Long.class);
        if(existing==null || existing!=0) {
            throw new IllegalStateException("Local bootstrap refused: staff accounts already exist");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id,login.toLowerCase(Locale.ROOT),encoder.encode(secret));
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,'ADMIN')",id);
        // Intentionally no username, password, token or account ID in logs.
    }
}
