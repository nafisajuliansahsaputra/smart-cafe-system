package com.natsx.smartcafe.system;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

/**
 * Read-only diagnostics before Flyway validates an already-initialized
 * synthetic-only Aiven review schema. Never repairs, deletes or creates rows.
 * Logs no passwords, connection strings, customer identifiers or SQL values.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scs.mysql.review-enabled", havingValue = "true")
public class ReviewFlywayDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(ReviewFlywayDiagnostics.class);

    @Bean
    FlywayMigrationStrategy syntheticReviewMigrationDiagnostics() {
        return flyway -> {
            try (Connection connection = flyway.getConfiguration()
                    .getDataSource().getConnection()) {
                if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                    throw new IllegalStateException("Review diagnostics require MySQL");
                }
                boolean failedV4 = scalar(connection, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'
                    """) > 0 && scalar(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history
                    WHERE version='4' AND success=0
                    """) > 0;
                long receiptsTables = scalar(connection, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name='receipts'
                    """);
                long receiptsTriggers = scalar(connection, """
                    SELECT COUNT(*) FROM information_schema.triggers
                    WHERE trigger_schema=DATABASE()
                      AND trigger_name IN ('receipt_no_update','receipt_no_delete')
                    """);
                long orderSnapshotTriggers = scalar(connection, """
                    SELECT COUNT(*) FROM information_schema.triggers
                    WHERE trigger_schema=DATABASE() AND trigger_name='order_snapshot_no_update'
                    """);
                log.info("SCS_REVIEW_FLYWAY_READ_ONLY failedV4={} receiptsTableCount={} " +
                        "receiptTriggers={} orderSnapshotTriggers={}",
                    failedV4, receiptsTables, receiptsTriggers, orderSnapshotTriggers);
            } catch (SQLException ex) {
                // Do not leak JDBC URL, provider details or credentials.
                log.warn("SCS_REVIEW_FLYWAY_READ_ONLY metadata unavailable; refusing to repair");
            }
            // Preserve Flyway's native strict validate/migrate behavior.
            flyway.migrate();
        };
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rows = stmt.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing scalar result");
            return rows.getLong(1);
        }
    }
}
