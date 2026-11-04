package com.natsx.smartcafe.system;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class MySqlPortReadinessGuardTest {
    private void check(String url) {
        var guard = new MySqlPortReadinessGuard();
        guard.setEnvironment(new MockEnvironment().withProperty("spring.datasource.url", url));
        guard.postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    @Test
    void mysqlFailsBeforeFlywayUntilJavaSqlAndAllTransactionsArePorted() {
        assertThrows(IllegalStateException.class,
            () -> check("jdbc:mysql://isolated.example:3306/smart_cafe_review"));
    }

    @Test
    void existingPostgresAndLocalDefaultsRemainUnaffected() {
        assertDoesNotThrow(() ->
            check("jdbc:postgresql://localhost:5432/smart_cafe"));
        var guard = new MySqlPortReadinessGuard();
        guard.setEnvironment(new MockEnvironment());
        assertDoesNotThrow(() ->
            guard.postProcessBeanFactory(new DefaultListableBeanFactory()));
    }
}
