package com.natsx.smartcafe.system;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

/**
 * No real Aiven credentials or data: prove that even an authenticated
 * container cannot run Flyway.migrate() on a partial schema.
 */
class HostedMySqlReviewFlywaySafetyTest {

    private record Fixtures(Flyway flyway, MigrationInfoService info) {}

    private Fixtures schema(int completed, MigrationState state, int pending) {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);

        MigrationInfo[] applied = new MigrationInfo[completed];
        Arrays.setAll(applied, i -> {
            MigrationInfo migration = mock(MigrationInfo.class);
            when(migration.getState()).thenReturn(state);
            return migration;
        });
        when(info.applied()).thenReturn(applied);
        MigrationInfo[] awaiting = new MigrationInfo[pending];
        Arrays.setAll(awaiting, i -> mock(MigrationInfo.class));
        when(info.pending()).thenReturn(awaiting);
        return new Fixtures(flyway, info);
    }

    @Test
    void completeSyntheticSchemaIsValidatedButNeverMigratedAtHttpStartup() {
        var fixture = schema(31, MigrationState.SUCCESS, 0);
        assertDoesNotThrow(() ->
            HostedMySqlReviewFlywaySafety.validatePriorMigration(fixture.flyway()));
        verify(fixture.flyway()).validate();
        // A single history snapshot avoids repeated Aiven metadata reads.
        verify(fixture.flyway()).info();
        verify(fixture.flyway(), never()).migrate();
    }

    @Test
    void emptyOrInterruptedFlywayHistoryCannotTriggerAutomaticRepair() {
        for (int count : new int[]{0, 1, 3, 4, 30}) {
            var fixture = schema(count, MigrationState.SUCCESS, 0);
            assertThrows(IllegalStateException.class, () ->
                HostedMySqlReviewFlywaySafety.validatePriorMigration(fixture.flyway()));
            verify(fixture.flyway(), never()).migrate();
        }
        var failed = schema(31, MigrationState.FAILED, 0);
        assertThrows(IllegalStateException.class, () ->
            HostedMySqlReviewFlywaySafety.validatePriorMigration(failed.flyway()));
        verify(failed.flyway(), never()).migrate();
    }

    @Test
    void pendingMigrationPreventsServingAnIncompleteOrNewerBinary() {
        var pending = schema(31, MigrationState.SUCCESS, 1);
        assertThrows(IllegalStateException.class, () ->
            HostedMySqlReviewFlywaySafety.validatePriorMigration(pending.flyway()));
        verify(pending.flyway(), never()).migrate();
    }
}
