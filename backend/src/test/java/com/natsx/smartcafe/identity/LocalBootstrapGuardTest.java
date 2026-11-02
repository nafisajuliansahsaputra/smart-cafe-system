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

    @Test
    void explicitlyAcknowledgedLocalDatabaseAndDisabledPaymentsAccepted() {
        var env = localEnvironment();
        assertDoesNotThrow(() -> LocalBootstrapGuard.requireSafeConfiguration(env));
        env.put("DB_URL", "jdbc:postgresql://localhost:5432/smart_cafe");
        assertDoesNotThrow(() -> LocalBootstrapGuard.requireSafeConfiguration(env));
        env.put("DB_URL", "jdbc:postgresql://127.0.0.1:5432/smart_cafe");
        assertDoesNotThrow(() -> LocalBootstrapGuard.requireSafeConfiguration(env));
        env.remove("DB_URL");
        assertDoesNotThrow(() -> LocalBootstrapGuard.requireSafeConfiguration(env));
    }

    @Test
    void missingOrIncorrectAcknowledgementRejected() {
        var env = localEnvironment();
        env.remove("SCS_BOOTSTRAP_LOCAL_ACK");
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env));
        env.put("SCS_BOOTSTRAP_LOCAL_ACK", "true");
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env));
    }

    @Test
    void mixedOrProductionProfileRejected() {
        var env = localEnvironment();
        for (String value : new String[] {"production", "dev-bootstrap,production", "",
            "production,dev-bootstrap"}) {
            env.put("SPRING_PROFILES_ACTIVE", value);
            assertThrows(IllegalStateException.class,
                () -> LocalBootstrapGuard.requireSafeConfiguration(env));
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
                () -> LocalBootstrapGuard.requireSafeConfiguration(env));
        }
    }

    @Test
    void enabledSandboxOrMerchantPaymentsRejected() {
        var env = localEnvironment();
        for (String paymentEnvironment : new String[] {"sandbox", "production", "live", ""}) {
            env.put("SCS_PAYMENT_ENVIRONMENT", paymentEnvironment);
            assertThrows(IllegalStateException.class,
                () -> LocalBootstrapGuard.requireSafeConfiguration(env));
        }
        env.put("SCS_PAYMENT_ENVIRONMENT", "disabled");
        env.put("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "true");
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env));
        env.put("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "false");
        env.put("SCS_SANDBOX_WEBHOOK_SECRET", "not-a-real-test-secret");
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env));
    }

    @Test
    void rejectionMessagesDoNotEchoSecretsOrDatabaseUrls() {
        var env = localEnvironment();
        env.put("DB_URL", "jdbc:postgresql://db.private:5432/live");
        env.put("SCS_BOOTSTRAP_PASSWORD", "private-not-for-logs");
        var error = assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requireSafeConfiguration(env));
        assertFalse(error.getMessage().contains("private-not-for-logs"));
        assertFalse(error.getMessage().contains("db.private"));
    }
}
