package com.natsx.smartcafe.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** No database, Flyway or real credentials: tests the earliest bean factory gate. */
class LocalBootstrapEarlyPreflightTest {

    @Test
    void rejectsUnsafeBootstrapBeforeAnyOrdinarySingletonStarts() {
        AtomicBoolean ordinaryBeanStarted = new AtomicBoolean(false);
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("dev-bootstrap");
        context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("override-database", Map.of(
                "spring.datasource.url", "jdbc:postgresql://production.example:5432/smart_cafe")));
        context.register(LocalBootstrapEarlyPreflight.class);
        context.registerBean("simulatedFlywayInitializer", Object.class, () -> {
            ordinaryBeanStarted.set(true);
            return new Object();
        });
        try {
            assertThrows(RuntimeException.class, context::refresh);
            assertFalse(ordinaryBeanStarted.get(),
                "Unsafe bootstrap must abort before any Flyway-like singleton is created");
        } finally {
            context.close();
        }
    }

    @Test
    void rejectsMixedProfilesAndRuntimeOverridesBeforeMigration() {
        var declared = Map.of(
            "SCS_BOOTSTRAP_LOCAL_ACK", "I_ACCEPT_LOCAL_ADMIN_BOOTSTRAP",
            "SPRING_PROFILES_ACTIVE", "dev-bootstrap",
            "DB_URL", "jdbc:postgresql://db:5432/smart_cafe");
        var safe = new LocalBootstrapGuard.EffectiveRuntime(
            "jdbc:postgresql://db:5432/smart_cafe", "disabled", false, false);
        LocalBootstrapGuard.requirePreMigrationConfiguration(
            declared, new String[] {"dev-bootstrap"}, safe);

        for (String[] profiles : new String[][] {
            {"dev-bootstrap", "production"}, {"production", "dev-bootstrap"}, {}, {"production"}
        }) {
            assertThrows(IllegalStateException.class,
                () -> LocalBootstrapGuard.requirePreMigrationConfiguration(
                    declared, profiles, safe));
        }
        assertThrows(IllegalStateException.class,
            () -> LocalBootstrapGuard.requirePreMigrationConfiguration(
                declared, new String[] {"dev-bootstrap"}, new LocalBootstrapGuard.EffectiveRuntime(
                    "jdbc:postgresql://production.example:5432/smart_cafe",
                    "disabled", false, false)));
        for (var runtime : new LocalBootstrapGuard.EffectiveRuntime[] {
            new LocalBootstrapGuard.EffectiveRuntime(safe.jdbcUrl(), "sandbox", false, false),
            new LocalBootstrapGuard.EffectiveRuntime(safe.jdbcUrl(), "disabled", true, false),
            new LocalBootstrapGuard.EffectiveRuntime(safe.jdbcUrl(), "disabled", false, true)
        }) {
            assertThrows(IllegalStateException.class,
                () -> LocalBootstrapGuard.requirePreMigrationConfiguration(
                    declared, new String[] {"dev-bootstrap"}, runtime));
        }
    }
}
