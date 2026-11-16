-- Test schema only (src/test/resources; application-test.yml adds this location).
--
-- The queue tests (shared.jobs.JobQueueIT) drive a scripted handler through the real queue. The job
-- table's CHECK allows only the production types, and since M8 every production type has a real
-- handler; two handlers for one type would be ambiguous (JobRunner refuses it). So the scripted
-- handler gets its own type, TEST, and only the test database allows it: production's schema stays
-- exactly what the migrations say.
--
-- A Flyway afterMigrate callback, not a migration: it isn't versioned or checksummed, so it can't
-- collide with V8+ and never ships. It runs after every migrate, hence idempotent.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                     FROM pg_constraint
                    WHERE conrelid = 'job'::regclass
                      AND conname = 'job_type_check'
                      AND pg_get_constraintdef(oid) LIKE '%TEST%') THEN
        ALTER TABLE job DROP CONSTRAINT IF EXISTS job_type_check;
        ALTER TABLE job ADD CONSTRAINT job_type_check
            CHECK (type IN ('IMPORT', 'RECONCILE', 'EXPORT', 'TEST'));
    END IF;
END
$$;
