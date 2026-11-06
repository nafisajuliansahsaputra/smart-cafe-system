package com.natsx.smartcafe.system;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The synthetic Aiven database is migrated exactly once via the explicit
 * owner-local review script. MySQL DDL is not transactional and Vercel cold
 * starts have already interrupted Flyway V4, leaving half-applied objects.
 *
 * A hosted request must NEVER retry a migration on a partial schema.
 * The live preview may only validate an already completed V1-V31 schema.
 * The normal PostgreSQL/default Flyway migration path is unaffected.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scs.mysql.review-enabled", havingValue = "true")
class HostedMySqlReviewFlywaySafety {

    @Bean
    FlywayMigrationStrategy alreadyMigratedReviewOnly() {
        return HostedMySqlReviewFlywaySafety::validatePriorMigration;
    }

    static void validatePriorMigration(Flyway flyway) {
        final String error =
            "Hosted synthetic MySQL review requires all 31 previously validated "
            + "Flyway migrations. Run the explicit owner-local empty-only "
            + "Aiven recovery first; HTTP startup never mutates the schema.";
        try {
            // Reuse a single Flyway history snapshot: Aiven network round trips are
            // costly on Hobby cold starts. No SQL DDL is executed.
            MigrationInfoService snapshot = flyway.info();
            MigrationInfo[] applied = snapshot.applied();
            if (applied.length != 31) throw new IllegalStateException(error);
            for (MigrationInfo migration : applied) {
                if (migration.getState() != MigrationState.SUCCESS) {
                    throw new IllegalStateException(error);
                }
            }
            if (snapshot.pending().length != 0) {
                throw new IllegalStateException(error);
            }
            // This checks checksums, file identity and failed migrations
            // without executing SQL DDL or altering schema history.
            flyway.validate();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Avoid leaking JDBC URLs or credential-bearing driver details
            // to a public container's startup error logs.
            throw new IllegalStateException(error);
        }
    }
}
