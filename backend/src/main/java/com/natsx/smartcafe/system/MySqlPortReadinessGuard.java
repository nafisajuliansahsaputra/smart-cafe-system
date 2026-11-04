package com.natsx.smartcafe.system;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Pre-Flyway fail-closed database gate.
 *
 * PostgreSQL is unchanged. MySQL normally fails startup. The only opt-in
 * exception is an explicitly acknowledged, isolated synthetic-only Aiven
 * review schema with full hostname-verified TLS and MySQL Flyway scripts.
 * This is NOT approval for merchant/production use or a MySQL cutover.
 */
@Component
public final class MySqlPortReadinessGuard
        implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final String ACK = "SCS_MYSQL_SYNTHETIC_REVIEW_ONLY";
    private static final Pattern REVIEW_DB =
        Pattern.compile("scs_review_[a-z0-9_]{4,32}");
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory)
            throws BeansException {
        String url = environment.getProperty("spring.datasource.url",
            "jdbc:postgresql://localhost:5432/smart_cafe");
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:")) {
            return;
        }
        // Every failure is deliberately generic: JDBC URL, credentials and
        // provider details must never appear in application/CI error logs.
        if (!"true".equals(environment.getProperty("scs.mysql.review-enabled"))
            || !ACK.equals(environment.getProperty("scs.mysql.review-ack"))
            || !"classpath:db/mysql".equals(environment.getProperty("spring.flyway.locations"))
            || !"true".equals(environment.getProperty("spring.flyway.enabled", "true"))
            || !"validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
            || !"disabled".equals(environment.getProperty("scs.payments.environment"))
            || !"false".equals(environment.getProperty("scs.payments.sandbox-digital-checkout-enabled", "false"))
            || !blank(environment.getProperty("scs.payments.sandbox-webhook-secret"))
            || blank(environment.getProperty("spring.datasource.password"))
            || blank(environment.getProperty("spring.datasource.username"))
            || hasDevBootstrapProfile(environment.getProperty("spring.profiles.active"))) {
            throw denied();
        }

        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException ex) {
            throw denied();
        }
        // Port and host are explicit to prevent accidentally hitting localhost,
        // a standard/default database, or unrelated provider accounts.
        String host = uri.getHost();
        String path = uri.getPath();
        if (!"mysql".equalsIgnoreCase(uri.getScheme())
            || host == null || !host.toLowerCase(Locale.ROOT).endsWith(".aivencloud.com")
            || uri.getPort() < 1
            || path == null || path.length() < 2
            || !REVIEW_DB.matcher(path.substring(1)).matches()
            || !hasStrictTlsMode(uri.getRawQuery())
            || uri.getUserInfo() != null
            || uri.getFragment() != null) {
            throw denied();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasDevBootstrapProfile(String value) {
        return value != null && Arrays.stream(value.split(","))
            .anyMatch(s -> s.trim().equalsIgnoreCase("dev-bootstrap"));
    }

    private static boolean hasStrictTlsMode(String query) {
        if (query == null || query.isBlank()) return false;
        boolean verified = false;
        boolean trustedCa = false;
        boolean explicitType = false;
        for (String item : query.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length != 2) continue;
            String key = pair[0].toLowerCase(Locale.ROOT);
            String value = pair[1];
            if (key.equals("sslmode")) {
                if (!"VERIFY_IDENTITY".equals(value) || verified) return false;
                verified = true;
            }
            if (key.equals("trustcertificatekeystoreurl")) {
                if (trustedCa
                    || !"file:/tmp/scs-aiven-truststore.p12".equals(value)) return false;
                trustedCa = true;
            }
            if (key.equals("trustcertificatekeystoretype")) {
                if (explicitType || !"PKCS12".equals(value)) return false;
                explicitType = true;
            }
            if (key.equals("allowpublickeyretrieval")
                || key.equals("trustservercertificate")
                || key.equals("fallbacktosystemtruststore")
                || key.equals("usessl")) {
                // These settings can silently undermine review TLS identity
                // or mask mistakes in the explicit trust configuration.
                return false;
            }
        }
        return verified && trustedCa && explicitType;
    }

    private static IllegalStateException denied() {
        return new IllegalStateException(
            "MySQL startup remains blocked. Only an explicitly acknowledged, "
            + "synthetic Aiven review database with VERIFY_IDENTITY, MySQL "
            + "Flyway scripts and disabled merchant payments may start.");
    }
}
