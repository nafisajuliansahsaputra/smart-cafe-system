package com.natsx.smartcafe.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalBootstrapGuardTest {
    private Map<String, String> localEnvironment() {
        return new HashMap<>(Map.of(
            "SPRING_PROFILES_ACTIVE", "dev-bootstrap",
            "SCS_BOOTSTRAP_LOCAL_ACK", "I_ACCEPT_LOCAL_ADMIN_BOOTSTRAP",
            "DB_URL", "jdbc:postgresql://db:5432/smart_cafe"
        ));
    }

    private LocalBootstrapGuard.EffectiveRuntime runtime(Map<String, String> env,
                                                          String jdbcUrl) {
        return new LocalBootstrapGuard.EffectiveRuntime(
            jdbcUrl,
            env.getOrDefault("SCS_PAYMENT_ENVIRONMENT", "disabled"),
            "true".equals(env.getOrDefault("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "false")),
            !env.getOrDefault("SCS_SANDBOX_WEBHOOK_SECRET", "").isEmpty());
    }

    private void check(Map<String, String> env) {
        LocalBootstrapGuard.requireSafeConfiguration(env, runtime(env,
            env.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/smart_cafe")));
    }

    @Test
    void runtimeDataSourceOverrideOrConnectionMismatchIsRejected() {
        var env = localEnvironment();
        // A higher-priority SPRING_DATASOURCE_URL can override the harmless DB_URL.
        env.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://merchant-db:5432/live");
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env,
                runtime(env, env.get("SPRING_DATASOURCE_URL"))));
        // Even a second allowlisted URL must match the declared target exactly.
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env,
                runtime(env, "jdbc:postgresql://localhost:5432/smart_cafe")));
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env, runtime(env, null)));
        assertDoesNotThrow(() -> LocalBootstrapGuard.requireSafeConfiguration(
            env, runtime(env, "jdbc:postgresql://db:5432/smart_cafe")));
    }

    @Test
    void resolvedSpringPaymentPropertiesOverrideSafeLookingEnvironment() {
        var env = localEnvironment();
        String local = env.get("DB_URL");
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env,
                new LocalBootstrapGuard.EffectiveRuntime(local, "sandbox", false, false)));
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env,
                new LocalBootstrapGuard.EffectiveRuntime(local, "disabled", true, false)));
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env,
                new LocalBootstrapGuard.EffectiveRuntime(local, "disabled", false, true)));
        assertDoesNotThrow(() -> LocalBootstrapGuard.requireSafeConfiguration(env,
            new LocalBootstrapGuard.EffectiveRuntime(local, "disabled", false, false)));
    }

    @Test
    void explicitlyAcknowledgedLocalDatabaseAndDisabledPaymentsAccepted() {
        var env = localEnvironment();
        assertDoesNotThrow(() -> check(env));
        env.put("DB_URL", "jdbc:postgresql://localhost:5432/smart_cafe");
        assertDoesNotThrow(() -> check(env));
        env.put("DB_URL", "jdbc:postgresql://127.0.0.1:5432/smart_cafe");
        assertDoesNotThrow(() -> check(env));
        env.remove("DB_URL");
        assertDoesNotThrow(() -> check(env));
    }

    @Test
    void missingOrIncorrectAcknowledgementRejected() {
        var env = localEnvironment();
        env.remove("SCS_BOOTSTRAP_LOCAL_ACK");
        assertThrows(IllegalStateException.class,
            () -> check(env));
        env.put("SCS_BOOTSTRAP_LOCAL_ACK", "true");
        assertThrows(IllegalStateException.class,
            () -> check(env));
    }

    @Test
    void mixedOrProductionProfileRejected() {
        var env = localEnvironment();
        for (String value : new String[] {"production", "dev-bootstrap,production", "",
            "production,dev-bootstrap"}) {
            env.put("SPRING_PROFILES_ACTIVE", value);
            assertThrows(IllegalStateException.class,
                () -> check(env));
        }
    }

    @Test
    void remoteDbAndUnexpectedDatabaseNamesRejected() {
        var env = localEnvironment();
        for (String url : new String[] {
            "jdbc:postgresql://merchant-db.example:5432/smart_cafe",
            "jdbc:postgresql://db:5432/prod",
            "jdbc:postgresql://db:5433/smart_cafe",
            "jdbc:postgresql://db:5432/smart_cafe?sslmode=disable",
            "jdbc:postgresql://localhost.evil.example:5432/smart_cafe"
        }) {
            env.put("DB_URL", url);
            assertThrows(IllegalStateException.class,
                () -> check(env));
        }
    }

    @Test
    void enabledSandboxOrMerchantPaymentsRejected() {
        var env = localEnvironment();
        for (String paymentEnvironment : new String[] {"sandbox", "production", "live", ""}) {
            env.put("SCS_PAYMENT_ENVIRONMENT", paymentEnvironment);
            assertThrows(IllegalStateException.class,
                () -> check(env));
        }
        env.put("SCS_PAYMENT_ENVIRONMENT", "disabled");
        env.put("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "true");
        assertThrows(IllegalStateException.class,
            () -> check(env));
        env.put("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "false");
        env.put("SCS_SANDBOX_WEBHOOK_SECRET", "not-a-real-test-secret");
        assertThrows(IllegalStateException.class,
            () -> check(env));
    }

    @Test
    void rejectionMessagesDoNotEchoSecretsOrDatabaseUrls() {
        var env = localEnvironment();
        env.put("DB_URL", "jdbc:postgresql://db.private:5432/live");
        env.put("SCS_BOOTSTRAP_PASSWORD", "private-not-for-logs");
        var error = assertThrows(IllegalStateException.class,
            () -> check(env));
        assertFalse(error.getMessage().contains("private-not-for-logs"));
        assertFalse(error.getMessage().contains("db.private"));
    }
}
