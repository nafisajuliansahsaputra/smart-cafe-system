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
 * Single-use bootstrap on an EMPTY dedicated Neon review database only.
 * Turn the profile OFF and REMOVE both credentials immediately after the
 * first successful deploy, before any regular public review traffic.
 */
@Component
@Profile("hosted-review-bootstrap")
public final class HostedReviewAdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Environment environment;

    public HostedReviewAdminBootstrap(
            JdbcTemplate jdbc, PasswordEncoder encoder, Environment environment) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String actual = jdbc.execute(
            (ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        var runtime = new LocalBootstrapGuard.EffectiveRuntime(
            actual,
            environment.getProperty("scs.payments.environment", "disabled"),
            environment.getProperty("scs.payments.sandbox-digital-checkout-enabled",
                Boolean.class, false),
            !environment.getProperty("scs.payments.sandbox-webhook-secret", "").isEmpty()
        );
        HostedReviewBootstrapGuard.requireSafeConfiguration(
            System.getenv(), environment.getActiveProfiles(), runtime);

        // Prevent simultaneous replicas from seeing zero users independently.
        jdbc.execute("SELECT pg_advisory_xact_lock(872646211)");
        Long users = jdbc.queryForObject("SELECT count(*) FROM staff_users", Long.class);
        if (users == null || users != 0L) {
            throw new IllegalStateException(
                "Hosted review bootstrap refused: an administrator already exists");
        }

        UUID id = UUID.randomUUID();
        String login = System.getenv("SCS_REVIEW_BOOTSTRAP_USERNAME")
            .toLowerCase(Locale.ROOT);
        String hash = encoder.encode(System.getenv("SCS_REVIEW_BOOTSTRAP_PASSWORD"));
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            id, login, hash);
        jdbc.update("INSERT INTO staff_role_assignments(staff_user_id,role_key) VALUES (?,'ADMIN')",
            id);
        // Never write login, password, hash, ID or database URL to logs.
    }
}
