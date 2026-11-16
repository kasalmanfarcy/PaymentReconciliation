-- Test schema only: the extension behind support.QueryCounter (M9.7's N+1 checks). The container
-- preloads the library (TestcontainersConfiguration); the extension adds the view. Idempotent.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
