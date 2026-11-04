package com.natsx.smartcafe.system;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class MySqlPortReadinessGuardTest {

    private static final String REVIEW_URL =
        "jdbc:mysql://mysql-review.aivencloud.com:12886/scs_review_synthetic"
        + "?sslMode=VERIFY_IDENTITY";

    private void check(MockEnvironment props) {
        var guard = new MySqlPortReadinessGuard();
        guard.setEnvironment(props);
        guard.postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    private MockEnvironment allowed() {
        return new MockEnvironment()
            .withProperty("spring.datasource.url", REVIEW_URL)
            .withProperty("spring.datasource.username", "synthetic-review-user")
            .withProperty("spring.datasource.password", "test-not-a-production-secret")
            .withProperty("spring.flyway.enabled", "true")
            .withProperty("spring.flyway.locations", "classpath:db/mysql")
            .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
            .withProperty("scs.mysql.review-enabled", "true")
            .withProperty("scs.mysql.review-ack", "SCS_MYSQL_SYNTHETIC_REVIEW_ONLY")
            .withProperty("scs.payments.environment", "disabled")
            .withProperty("scs.payments.sandbox-digital-checkout-enabled", "false")
            .withProperty("scs.payments.sandbox-webhook-secret", "");
    }

    @Test
    void mysqlStillFailsClosedByDefault() {
        assertThrows(IllegalStateException.class, () -> check(new MockEnvironment()
            .withProperty("spring.datasource.url", REVIEW_URL)));
    }

    @Test
    void onlyExplicitSyntheticAivenReviewSchemaWithVerifiedTlsCanStart() {
        assertDoesNotThrow(() -> check(allowed()));
    }

    @Test
    void refuseDefaultDatabasesAndPublicMysqlProviders() {
        for (String url : new String[] {
            "jdbc:mysql://mysql-review.aivencloud.com:12886/defaultdb?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://mysql-review.aivencloud.com:12886/smart_cafe?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://localhost:3306/scs_review_synthetic?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://evil.example:3306/scs_review_synthetic?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://mysql-review.aivencloud.com:12886/scs_review_synthetic?sslMode=DISABLED",
            "jdbc:mysql://mysql-review.aivencloud.com:12886/scs_review_synthetic",
            "jdbc:mysql://mysql-review.aivencloud.com:12886/scs_review_synthetic?sslMode=VERIFY_IDENTITY&trustServerCertificate=true",
            "jdbc:mysql://mysql-review.aivencloud.com:12886/scs_review_synthetic?sslMode=VERIFY_IDENTITY&sslMode=DISABLED",
            "jdbc:mysql://mysql-review.aivencloud.com:12886/scs_review_synthetic?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=true",
            "jdbc:mysql://mysql-review.aivencloud.com:12886/scs_review_synthetic?sslMode=VERIFY_IDENTITY&useSSL=false"
        }) {
            assertThrows(IllegalStateException.class,
                () -> check(allowed().withProperty("spring.datasource.url", url)));
        }
    }

    @Test
    void rejectWrongDialectCredentialsAndMerchantPayments() {
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("spring.flyway.locations", "classpath:db/migration")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("scs.mysql.review-ack", "YES")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("scs.mysql.review-enabled", "false")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("spring.datasource.password", "")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("spring.flyway.enabled", "false")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("spring.jpa.hibernate.ddl-auto", "update")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("scs.payments.environment", "sandbox")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("scs.payments.sandbox-digital-checkout-enabled", "true")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("scs.payments.sandbox-webhook-secret", "test-signing-key")));
        assertThrows(IllegalStateException.class, () -> check(allowed()
            .withProperty("spring.profiles.active", "dev-bootstrap")));
    }

    @Test
    void existingPostgresAndLocalDefaultsRemainUnaffected() {
        assertDoesNotThrow(() -> check(new MockEnvironment()
            .withProperty("spring.datasource.url",
                "jdbc:postgresql://localhost:5432/smart_cafe")));
        assertDoesNotThrow(() -> check(new MockEnvironment()));
    }
}
