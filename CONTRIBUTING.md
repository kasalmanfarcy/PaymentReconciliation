# Contributing to ReconFlow

## Workflow

1. Pick a task from [TASKS.md](TASKS.md). Don't start a milestone until the
   previous one's exit criteria hold.
2. Branch from an up-to-date `main`:
   `git switch main && git pull && git switch -c <type>/<task-id>-<slug>`
   e.g. `feat/M5.11-row-validator`, `fix/M7.8-stale-version-409`, `docs/M1.5-rules`.
3. Commit in small steps using the message format below.
4. Open a pull request into `main`. CI must pass before merging.
5. Merge with **squash and merge**. The PR title becomes the commit message, so
   it must follow the format too.
6. Tick the task in TASKS.md in the same PR that completes it.

`main` is protected: no direct pushes, no force pushes, PRs only.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/) with a task ID:

    <type>(<scope>): <task-id> <imperative summary, max 72 chars>

    <body: why the change was needed, wrapped at 72 columns>

Types: `feat`, `fix`, `perf`, `docs`, `test`, `refactor`, `build`, `ci`, `chore`. `perf` is a
change whose purpose is speed or resource use, backed by a measurement (M9.7).

Scopes: the module (`organization`, `ingestion`, `ledger`, `reconciliation`,
`exceptions`, `reporting`, `audit`, `security`, `shared`) or `repo` for
repository-wide changes.

Examples:

    feat(ingestion): M5.11 validate amount scale and ISO currency
    fix(exceptions): M7.8 return 409 when If-Match is stale
    docs(repo): M1.5 add reconciliation rules
    build(repo): M2.3 add Testcontainers

Commits made before this convention was adopted are left as they are;
pushed history on `main` is never rewritten.

## Definition of done (every PR)

- [ ] `./mvnw verify` passes locally
- [ ] Money is `BigDecimal` / `NUMERIC`, compared with `compareTo`
- [ ] Every tenant-owned query is scoped by `organizationId`: tenant repositories extend
      `Repository` (not `JpaRepository`) and every `find`/`count`/`exists`/`delete` method takes
      an `organizationId` parameter (`TenantRepositoryConventionTest` enforces it); the ID comes
      from `TenantContext`, never from the request
- [ ] A new tenant-owned table gets `ENABLE` + `FORCE ROW LEVEL SECURITY` and a policy in its
      migration (`RlsCoverageIT` fails otherwise)
- [ ] New endpoints have cross-tenant and authorization tests
- [ ] State changes go through the central transition logic and emit an audit event
- [ ] Schema changes are a new Flyway migration; applied migrations are never edited
- [ ] List endpoints paginate
- [ ] Docs in `docs/` are updated when behaviour changes

## Migrations

- Next free `V` number; never edit an applied migration (Flyway rejects the changed checksum).
- **Migrations run as `reconflow_migrator`**, the schema owner (M10.7); the application runs as
  `reconflow_app`, which can't run DDL. A new table needs no `GRANT`: `V10`'s default privileges
  give the app data access to every table the migrator creates. Revoke explicitly what it must not
  have (an append-only table: `REVOKE UPDATE, DELETE, TRUNCATE ... FROM reconflow_app`).
- **Anything the application must do that needs ownership** (DDL, `ANALYZE`) goes through a narrow
  `SECURITY DEFINER` function with `SET search_path = public, pg_temp`, `EXECUTE` revoked from
  `PUBLIC` and granted to `reconflow_app` (`AppRolePrivilegesIT` checks the last two). Never give the
  app ownership or `BYPASSRLS`.
- **Data backfills on tenant-owned tables and row-level security.** Since `V4`, every
  tenant-owned table has `FORCE ROW LEVEL SECURITY`, so even the owner (the migrator) sees only the
  rows of the organization in `app.org_id`, and none when it isn't set. A migration that reads or
  updates existing rows must loop over organizations with
  `SELECT set_config('app.org_id', <org>, true)` per organization. Schema changes (DDL) are
  unaffected.
- **Migrations run as their own deploy step, never from the app (M11.5).** The `migrate` role
  (`SPRING_PROFILES_ACTIVE=prod,migrate`) applies them with the migrator's credentials and exits;
  deployed api and worker instances only validate, as `reconflow_app`, and refuse to start while a
  migration is pending (`FlywayValidateOnlyIT`). `local` and `test` still migrate on startup.
- **Old and new code run against the same schema during a deploy, and rollback means redeploying
  the previous image** (Flyway Community has no undo). So every migration must work with the
  previous release: **expand/contract**. Add first (a nullable column, a new table, a backfill in
  batches), switch the code in the next release, drop the old structure in the one after. M10.1's
  auth columns are an example: the contract step waits in `infra/db/contract/` until the release
  after the switch.
- **Keep locks short** (PostgreSQL 16): `ADD COLUMN` with a constant default is metadata-only; a
  `NOT NULL` or `CHECK` on a big table goes in as `NOT VALID`, then `VALIDATE CONSTRAINT`;
  `CREATE INDEX CONCURRENTLY` can't run in a transaction (`-- flyway:executeInTransaction=false` on
  its own migration); start migrations that take locks with `SET lock_timeout = '5s'` so a blocked
  `ALTER` fails the deploy instead of queueing every query behind it.
- **Every migration from `V13` on starts with a rollback note** (`scripts/check_migration_notes.sh`
  runs in CI; `V1`–`V12` predate the rule and must not be touched):

      -- V13__exception_reason_expand.sql
      -- Purpose : add exception_case.reason (expand step of the reason_code rename).
      -- Rollback: safe to leave in place; the previous release ignores the column.
      --           To remove: a forward migration DROP COLUMN reason, after reverting the code.
      -- Locks   : ADD COLUMN without a default is metadata-only.

  For a destructive change the note says what data is lost and points to the restore procedure
  ([docs/runbooks/restore-drill.md](docs/runbooks/restore-drill.md)).
- **Tenant data is touched only inside a transaction.** The tenant reaches the database through
  `TenantTransactionListener` when a transaction begins; a repository call outside one sees no
  rows. A derived query on a plain `Repository` interface is not transactional by itself.

## Local setup

See the README's "Getting started".
