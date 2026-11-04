package com.natsx.smartcafe.identity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Guards a single-use administrator bootstrap on a new, isolated Neon
 * PostgreSQL review database. Never enabled in normal application startup.
 * No production/payment DB, account reuse, or environment override is allowed.
 */
final class HostedReviewBootstrapGuard {
    private static final String ACK = "I_ACCEPT_ONE_TIME_HOSTED_REVIEW_ADMIN";

    private HostedReviewBootstrapGuard() {}

    static void requireSafeConfiguration(
            Map<String, String> vars, String[] profiles,
            LocalBootstrapGuard.EffectiveRuntime runtime) {
        if (profiles == null || profiles.length != 1
                || !"hosted-review-bootstrap".equals(profiles[0])
                || !"hosted-review-bootstrap".equals(vars.get("SPRING_PROFILES_ACTIVE"))) {
            throw new IllegalStateException("Hosted review bootstrap needs its isolated profile");
        }
        if (!ACK.equals(vars.get("SCS_REVIEW_BOOTSTRAP_ACK"))) {
            throw new IllegalStateException("Hosted review bootstrap requires explicit acknowledgement");
        }
        String login = vars.get("SCS_REVIEW_BOOTSTRAP_USERNAME");
        String password = vars.get("SCS_REVIEW_BOOTSTRAP_PASSWORD");
        if (login == null || !login.matches("[A-Za-z][A-Za-z0-9._-]{2,39}")
                || password == null || password.length() < 24 || password.length() > 128) {
            throw new IllegalStateException("Hosted review bootstrap credentials are invalid");
        }
        String declaredUrl = vars.get("DB_URL");
        if (runtime == null || declaredUrl == null
                || !safeNeonReviewDatabase(declaredUrl)
                || !declaredUrl.equals(runtime.jdbcUrl())) {
            throw new IllegalStateException("Hosted review bootstrap requires its exact Neon review database");
        }
        if (!"disabled".equals(vars.getOrDefault("SCS_PAYMENT_ENVIRONMENT", "disabled"))
                || !"false".equals(vars.getOrDefault("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "false"))
                || !vars.getOrDefault("SCS_SANDBOX_WEBHOOK_SECRET", "").isEmpty()
                || !"disabled".equals(runtime.paymentEnvironment())
                || runtime.sandboxCheckoutEnabled() || runtime.webhookConfigured()) {
            throw new IllegalStateException("Hosted review bootstrap refuses enabled payment settings");
        }
    }

    static boolean safeNeonReviewDatabase(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            return false;
        }
        try {
            URI uri = new URI(jdbcUrl.substring(5));
            String hostname = uri.getHost();
            if (hostname == null || !hostname.endsWith(".neon.tech")
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 5432
                    || !"/smart_cafe_review".equals(uri.getPath())
                    || uri.getFragment() != null) {
                return false;
            }
            String query = uri.getRawQuery();
            return "sslmode=require".equals(query)
                    || "sslmode=verify-full".equals(query);
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}
