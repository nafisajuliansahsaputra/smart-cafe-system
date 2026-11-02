package com.natsx.smartcafe.identity;

import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
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
    private final Environment environment;

    public LocalAdminBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder, Environment environment) {
        this.jdbc=jdbc;
        this.encoder=encoder;
        this.environment=environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Inspect the connection Spring actually selected. A higher-priority
        // spring.datasource.url override must never bypass DB_URL policy.
        String actualUrl = jdbc.execute(
            (ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        // Resolve the same Spring properties the synthetic payment components
        // use. Higher-precedence system/command-line properties must not bypass
        // the environment-only bootstrap guard.
        var runtime = new LocalBootstrapGuard.EffectiveRuntime(
            actualUrl,
            environment.getProperty("scs.payments.environment", "disabled"),
            environment.getProperty("scs.payments.sandbox-digital-checkout-enabled",
                Boolean.class, false),
            !environment.getProperty("scs.payments.sandbox-webhook-secret", "").isEmpty());
        LocalBootstrapGuard.requireSafeConfiguration(System.getenv(), runtime);
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
