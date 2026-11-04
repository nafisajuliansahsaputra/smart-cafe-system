package com.natsx.smartcafe.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostedReviewBootstrapGuardTest {
    private static final String URL =
        "jdbc:postgresql://ep-fresh-db.us-east-2.aws.neon.tech/smart_cafe_review?sslmode=require";

    private Map<String, String> env() {
        var vars = new HashMap<String, String>();
        vars.put("SPRING_PROFILES_ACTIVE", "hosted-review-bootstrap");
        vars.put("SCS_REVIEW_BOOTSTRAP_ACK", "I_ACCEPT_ONE_TIME_HOSTED_REVIEW_ADMIN");
        vars.put("SCS_REVIEW_BOOTSTRAP_USERNAME", "review-owner");
        vars.put("SCS_REVIEW_BOOTSTRAP_PASSWORD", "A-unique-test-only-secret-78900");
        vars.put("DB_URL", URL);
        return vars;
    }

    private LocalBootstrapGuard.EffectiveRuntime runtime(String url) {
        return new LocalBootstrapGuard.EffectiveRuntime(url, "disabled", false, false);
    }

    private void check(Map<String, String> vars, String jdbcUrl) {
        HostedReviewBootstrapGuard.requireSafeConfiguration(
            vars, new String[]{"hosted-review-bootstrap"}, runtime(jdbcUrl));
    }

    @Test
    void exactReviewNeonDbAndDisabledPaymentsPass() {
        assertDoesNotThrow(() -> check(env(), URL));
        assertTrue(HostedReviewBootstrapGuard.safeNeonReviewDatabase(URL));
    }

    @Test
    void otherHostsDbNamesOrTlsSettingsNeverPass() {
        for (String url : new String[] {
            "jdbc:postgresql://localhost:5432/smart_cafe_review?sslmode=require",
            "jdbc:postgresql://neon.tech.evil.tld/smart_cafe_review?sslmode=require",
            "jdbc:postgresql://ep-test.aws.neon.tech/live?sslmode=require",
            "jdbc:postgresql://ep-test.aws.neon.tech/smart_cafe_review?sslmode=disable",
            "jdbc:postgresql://ep-test.aws.neon.tech/smart_cafe_review",
            "jdbc:postgresql://ep-test.aws.neon.tech/smart_cafe_review?sslmode=require&login=other",
            "jdbc:postgresql://user:pass@ep-test.aws.neon.tech/smart_cafe_review?sslmode=require",
            "jdbc:postgresql://ep-test.aws.neon.tech:5433/smart_cafe_review?sslmode=require",
            "jdbc:postgresql://ep-test.aws.neon.tech/smart_cafe_review?sslmode=require#fragment",
            "jdbc:postgresql://ep-test.aws.neon.tech/smart_cafe_review?sslmode=require%26sslmode%3Ddisable",
            "jdbc:postgresql://ep-test.aws.neon.tech/smart_cafe_review?sslmode=require/extra"
        }) {
            assertFalse(HostedReviewBootstrapGuard.safeNeonReviewDatabase(url), url);
            Map<String, String> vars = env();
            vars.put("DB_URL", url);
            assertThrows(IllegalStateException.class, () -> check(vars, url));
        }
    }

    @Test
    void resolvedDatasourceOverrideOrDifferentConnectionRejected() {
        Map<String, String> vars = env();
        assertThrows(IllegalStateException.class, () -> check(vars,
            "jdbc:postgresql://ep-other.aws.neon.tech/smart_cafe_review?sslmode=require"));
        vars.put("DB_URL", URL.replace("ep-fresh-db", "ep-another"));
        assertThrows(IllegalStateException.class, () -> check(vars, URL));
    }

    @Test
    void profileCredentialsAndAckMandatory() {
        Map<String, String> vars = env();
        for (String wrong : new String[]{"production", "dev-bootstrap", "",
            "hosted-review-bootstrap,production"}) {
            vars.put("SPRING_PROFILES_ACTIVE", wrong);
            assertThrows(IllegalStateException.class, () -> check(vars, URL));
        }
        vars.put("SPRING_PROFILES_ACTIVE", "hosted-review-bootstrap");
        assertThrows(IllegalStateException.class,
            () -> HostedReviewBootstrapGuard.requireSafeConfiguration(
                vars, new String[]{"hosted-review-bootstrap", "production"}, runtime(URL)));
        vars.remove("SCS_REVIEW_BOOTSTRAP_ACK");
        assertThrows(IllegalStateException.class, () -> check(vars, URL));
        vars.put("SCS_REVIEW_BOOTSTRAP_ACK", "I_ACCEPT_ONE_TIME_HOSTED_REVIEW_ADMIN");
        vars.put("SCS_REVIEW_BOOTSTRAP_PASSWORD", "weak");
        assertThrows(IllegalStateException.class, () -> check(vars, URL));
        vars.put("SCS_REVIEW_BOOTSTRAP_PASSWORD", "A-unique-test-only-secret-78900");
        vars.put("SCS_REVIEW_BOOTSTRAP_USERNAME", "no spaces or unicode");
        assertThrows(IllegalStateException.class, () -> check(vars, URL));
    }

    @Test
    void paymentOverridesFailClosedWithoutLeakingSecrets() {
        Map<String, String> vars = env();
        for (String paymentEnvironment : new String[]{"sandbox", "production"}) {
            vars.put("SCS_PAYMENT_ENVIRONMENT", paymentEnvironment);
            assertThrows(IllegalStateException.class, () -> check(vars, URL));
        }
        vars.put("SCS_PAYMENT_ENVIRONMENT", "disabled");
        vars.put("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "true");
        assertThrows(IllegalStateException.class, () -> check(vars, URL));
        vars.put("SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED", "false");
        vars.put("SCS_SANDBOX_WEBHOOK_SECRET", "secret-must-not-leak");
        var error = assertThrows(IllegalStateException.class, () -> check(vars, URL));
        assertFalse(error.getMessage().contains("secret-must-not-leak"));
        assertFalse(error.getMessage().contains("ep-fresh-db"));
    }
}
