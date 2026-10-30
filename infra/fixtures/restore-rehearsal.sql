-- CI-only synthetic restore sentinel, after e2e-browser.sql on a fresh DB.
-- Never apply to real cafe/merchant data.
INSERT INTO audit_logs(id,actor_type,actor_id,operation,resource_type,resource_id,correlation_id)
VALUES ('99999999-9999-4999-8999-99999999a001','STAFF',
        '44444444-4444-4444-8444-444444444444',
        'CI_RESTORE_SYNTHETIC_PROBE','PHYSICAL_GAME_COPY',
        'ffffffff-ffff-4fff-8fff-ffffffff2222',
        'ci-restore-synthetic-only');
