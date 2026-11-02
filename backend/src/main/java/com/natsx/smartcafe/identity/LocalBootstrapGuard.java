package com.natsx.smartcafe.identity;

import java.util.Map;

/**
 * Additional fail-closed guard for the one-time, disposable LOCAL development
 * administrator bootstrap. This is not a production provisioning mechanism.
 *
 * A local confirmation plus narrow database/environment checks are required
 * even when the dev-bootstrap Spring profile is accidentally enabled.
 */
final class LocalBootstrapGuard {
    private static final String ACK = "I_ACCEPT_LOCAL_ADMIN_BOOTSTRAP";
    private static final String DEFAULT_LOCAL_DB = "jdbc:postgresql://localhost:5432/smart_cafe";

    private LocalBootstrapGuard() {}

    record EffectiveRuntime(String jdbcUrl, String paymentEnvironment,
                            boolean sandboxCheckoutEnabled, boolean webhookConfigured) {}

    /**
     * Validate configuration before the DataSource and Flyway migration beans
     * can initialize. A later check compares against the actual JDBC metadata.
     */
    static void requirePreMigrationConfiguration(Map<String, String> env,
                                                 String[] activeProfiles,
                                                 EffectiveRuntime effective) {
        if (activeProfiles == null || activeProfiles.length != 1
            || !"dev-bootstrap".equals(activeProfiles[0])) {
            throw new IllegalStateException(
                "Local admin bootstrap requires the isolated development profile");
        }
        requireSafeConfiguration(env, effective);
    }

    static void requireSafeConfiguration(Map<String, String> env, EffectiveRuntime runtime) {
        if (!ACK.equals(env.get("SCS_BOOTSTRAP_LOCAL_ACK"))) {
            throw new IllegalStateException(
                "Local admin bootstrap requires explicit local-only acknowledgement");
        }
        if (!"dev-bootstrap".equals(env.get("SPRING_PROFILES_ACTIVE"))) {
            throw new IllegalStateException(
                "Local admin bootstrap requires the isolated dev-bootstrap profile");
        }
        // Both the declared DB_URL and the live JDBC connection must satisfy
        // this allowlist. Spring environment/property overrides can redirect the
        // configured DataSource even when DB_URL still looks local.
        String declaredUrl = env.getOrDefault("DB_URL", DEFAULT_LOCAL_DB);
        if (runtime == null || !allowedLocalDatabase(declaredUrl)
            || !allowedLocalDatabase(runtime.jdbcUrl())
            || !declaredUrl.equals(runtime.jdbcUrl())) {
            throw new IllegalStateException(
                "Local admin bootstrap requires the dedicated local smart_cafe database");
        }
        if (!"disabled".equals(env.getOrDefault("SCS_PAYMENT_ENVIRONMENT", "disabled"))
            || !"false".equals(env.getOrDefault("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "false"))
            || !env.getOrDefault("SCS_SANDBOX_WEBHOOK_SECRET", "").isEmpty()
            || !"disabled".equals(runtime.paymentEnvironment())
            || runtime.sandboxCheckoutEnabled()
            || runtime.webhookConfigured()) {
            throw new IllegalStateException(
                "Local admin bootstrap refuses any enabled payment environment");
        }
    }
    private static boolean allowedLocalDatabase(String url) {
        return url != null && url.matches(
            "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1|db):5432/smart_cafe");
    }
}
