package com.natsx.smartcafe.system;

import java.util.Objects;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Do not run a financial application on a database whose migration and
 * repository query parity have not yet been fully reviewed. Pre-Flyway guard
 * ensures accidentally setting DB_URL to MySQL cannot create a partly
 * migrated public POS. Remove only after MySQL V1-V31 and E2E pass.
 */
@Component
public final class MySqlPortReadinessGuard
        implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory)
            throws BeansException {
        String url = environment.getProperty(
            "spring.datasource.url", "jdbc:postgresql://localhost:5432/smart_cafe");
        if (url != null && url.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:mysql:")) {
            throw new IllegalStateException(
                "MySQL 8 support is under financial-parity migration. " +
                "Do not start a real cafe service until all migrations and " +
                "cash-to-receipt, stock and game-loan tests pass on MySQL.");
        }
    }
}
