-- Fencing for one day-chunk: lock the run row (serializing with any other
-- worker on this run) and read where it is. The caller expects RUNNING and
-- last_completed_date = :day - 1 (NULL on the first day of the period).
SELECT status, last_completed_date
  FROM reconciliation_run
 WHERE id = :runId AND organization_id = :org
   FOR UPDATE
