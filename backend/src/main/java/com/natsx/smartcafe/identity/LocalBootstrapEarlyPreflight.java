package com.natsx.smartcafe.identity;

import java.util.Objects;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail closed at BeanFactoryPostProcessor time, before Flyway can initialize
 * the DataSource or run migrations. Only loaded for the explicit dev profile.
 * The normal bootstrap runner still verifies the actual JDBC connection.
 */
@Component
@Profile("dev-bootstrap")
public final class LocalBootstrapEarlyPreflight
        implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        // Spring's resolved property can differ from DB_URL when an operator
        // supplies command-line/system-property overrides.
        String resolvedJdbc = environment.getProperty("spring.datasource.url",
            "jdbc:postgresql://localhost:5432/smart_cafe");
        var effective = new LocalBootstrapGuard.EffectiveRuntime(
            resolvedJdbc,
            environment.getProperty("scs.payments.environment", "disabled"),
            environment.getProperty("scs.payments.sandbox-digital-checkout-enabled",
                Boolean.class, false),
            !environment.getProperty("scs.payments.sandbox-webhook-secret", "").isEmpty());
        LocalBootstrapGuard.requirePreMigrationConfiguration(
            System.getenv(), environment.getActiveProfiles(), effective);
    }
}
