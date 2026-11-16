-- The checkpoint, in the same transaction as the day's matches and cases.
-- SQL, not the entity, so a stale entity can never overwrite it; and SQL
-- bypasses @UpdateTimestamp, so updated_at is set here.
UPDATE reconciliation_run
   SET last_completed_date = :day, updated_at = now()
 WHERE id = :runId AND organization_id = :org AND status = 'RUNNING'
