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
 * Fail closed before Flyway touches a remote database if the user explicitly
 * requests the ONE-TIME hosted-review-bootstrap profile.
 */
@Component
@Profile("hosted-review-bootstrap")
public final class HostedReviewBootstrapEarlyPreflight
        implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory)
            throws BeansException {
        var effective = new LocalBootstrapGuard.EffectiveRuntime(
            environment.getProperty("spring.datasource.url",
                "jdbc:postgresql://localhost:5432/smart_cafe"),
            environment.getProperty("scs.payments.environment", "disabled"),
            environment.getProperty("scs.payments.sandbox-digital-checkout-enabled",
                Boolean.class, false),
            !environment.getProperty("scs.payments.sandbox-webhook-secret", "").isEmpty()
        );
        HostedReviewBootstrapGuard.requireSafeConfiguration(
            System.getenv(), environment.getActiveProfiles(), effective);
    }
}
