package com.natsx.smartcafe.identity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Guards a single-use administrator bootstrap on a new, isolated Neon
 * PostgreSQL or Aiven MySQL synthetic review database.
 * Never enabled in normal application startup.
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
        boolean isAiven = safeAivenReviewDatabase(declaredUrl);
        boolean isNeon = safeNeonReviewDatabase(declaredUrl);
        if (runtime == null || declaredUrl == null
                || !(isNeon || isAiven)
                || !declaredUrl.equals(runtime.jdbcUrl())) {
            throw new IllegalStateException(
                "Hosted review bootstrap requires its exact isolated review database");
        }
        if (isAiven && (!"true".equals(vars.get("SCS_MYSQL_REVIEW_ENABLED"))
                || !"SCS_MYSQL_SYNTHETIC_REVIEW_ONLY".equals(vars.get("SCS_MYSQL_REVIEW_ACK"))
                || !"classpath:db/mysql".equals(vars.get("SCS_FLYWAY_LOCATIONS"))
                || isBlank(vars.get("SCS_AIVEN_CA_PEM_BASE64"))
                || isBlank(vars.get("DB_USERNAME"))
                || isBlank(vars.get("DB_PASSWORD")))) {
            throw new IllegalStateException(
                "Hosted Aiven review bootstrap requires verified isolated MySQL review settings");
        }
        if (!"disabled".equals(vars.getOrDefault("SCS_PAYMENT_ENVIRONMENT", "disabled"))
                || !"false".equals(vars.getOrDefault("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "false"))
                || !vars.getOrDefault("SCS_SANDBOX_WEBHOOK_SECRET", "").isEmpty()
                || !"disabled".equals(runtime.paymentEnvironment())
                || runtime.sandboxCheckoutEnabled() || runtime.webhookConfigured()) {
            throw new IllegalStateException("Hosted review bootstrap refuses enabled payment settings");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Unlike normal MySQL review startup, the single-use admin initializer
     * accepts ONLY these four fixed JDBC query settings. No dynamic extra
     * properties, aliases, weak TLS, extra databases or userinfo.
     */
    static boolean safeAivenReviewDatabase(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) return false;
        try {
            URI uri = new URI(jdbcUrl.substring(5));
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || !host.toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".aivencloud.com")
                || uri.getPort() < 1
                || path == null || !path.matches("/scs_review_[a-z0-9_]{4,32}")
                || uri.getUserInfo() != null || uri.getFragment() != null) {
                return false;
            }
            String query = uri.getRawQuery();
            if (query == null) return false;
            java.util.Map<String,String> exact = java.util.Map.of(
                "sslMode","VERIFY_IDENTITY",
                "trustCertificateKeyStoreUrl","file:/tmp/scs-aiven-truststore.p12",
                "trustCertificateKeyStoreType","PKCS12",
                "trustCertificateKeyStorePassword","scs-public-ca-only");
            java.util.Map<String,String> actual = new java.util.HashMap<>();
            for (String param : query.split("&",-1)) {
                String[] item = param.split("=",2);
                if (item.length != 2 || !exact.containsKey(item[0])
                    || !exact.get(item[0]).equals(item[1])
                    || actual.putIfAbsent(item[0],item[1]) != null) return false;
            }
            return actual.equals(exact);
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return false;
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
