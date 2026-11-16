# Restore drill (M9.17)

A backup that has never been restored is a hope. Quarterly (and after any change to the schema's shape or the database's size class), restore the latest backup into a **fresh** instance, start the app against it, check it, and record the time below. That time is the RTO this setup actually achieves (target: ≤ 1 h, docs/scalability.md §6).

## How

Locally: `scripts/restore_drill.sh <org-id> <run-id>` (with `SOURCE_BASE=http://localhost:8080` to compare a report against the running source). It:

1. takes a logical backup: `pg_dump -Fc`;
2. starts a throwaway `postgres:16.4-alpine` (a fresh instance, not a second database in the same one);
3. restores with `pg_restore --no-owner --jobs 4`, then `ANALYZE` (a restored database has no statistics, and §2.2/§2.4 of docs/benchmarks.md show what planning without them costs);
4. compares row counts of the main tables and a known run's totals between source and copy;
5. starts the packaged app against the copy (`local,api`, seeding off, other ports): Flyway validates every applied migration's checksum and Hibernate validates the schema; readiness must be `UP`; the known run's reconciliation report must be byte-identical to the source's.

Once deployed (M11), the same steps against the managed provider's point-in-time recovery: restore to a timestamp into a new instance, point a staging app at it, run steps 4–5.

**Roles.** `pg_dump` carries grants but no roles. Since M10.7 the application connects as `reconflow_app` and Flyway as `reconflow_migrator`, so the drill first runs `infra/db/bootstrap-roles.sql` on the fresh instance (new passwords), then restores with `--no-owner --role=reconflow_migrator`, which makes the migrator the owner of everything whoever owned it at the source, while the dump's `GRANT`/`REVOKE` statements restore the app role's privileges. Skipping the roles fails one of two ways: the grants fail and the app can read nothing, or someone "fixes" it by pointing the app at the owner and row-level security is silently bypassed. That was the drill finding predicted in M9; `scripts/restore_drill.sh` now does it.

## Log

| Date | Source | Backup | Instance ready | Restore + ANALYZE | App ready | Total | Checks | Problems found |
|---|---|---|---|---|---|---|---|---|
| 2026-11-16 | Benchmark database (Colima, docs/benchmarks.md §0): 6.15 M `transaction_record` rows, 1.61 M matches, 236 k cases, 236 k audit events; 2.9 GB on disk | 464 MB `pg_dump -Fc` in 25.8 s | 3.4 s | 67.9 s | 4.3 s | **102 s** (RTO target 3,600 s) | 8 row counts and a run's totals identical; Flyway and Hibernate validation passed; reconciliation report for preload organization 1's October run byte-identical to the source's | The first version of the script restored from stdin, which `pg_restore --jobs` doesn't support; it now copies the dump into the container. Nothing wrong with the backup itself |

The restore took about 1.1 s per 100 k records here; at the 1,000-user target's 13 hot months (~600 M records, ~450 GB) a logical restore would take far longer than an hour, which is why production relies on the managed provider's physical PITR (scalability.md §6), and why the drill must be repeated there in M11 to measure the real RTO.
