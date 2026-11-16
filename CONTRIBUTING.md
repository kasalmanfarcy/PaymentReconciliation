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

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci`, `chore`.

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
- **Data backfills on tenant-owned tables and row-level security.** Since `V4`, every
  tenant-owned table has `FORCE ROW LEVEL SECURITY`, so even the table owner sees only the rows
  of the organization in `app.org_id`, and none when it isn't set. Until M10.7 separates the
  roles, a migration that reads or updates existing rows must either run as a superuser or loop
  over organizations with `SELECT set_config('app.org_id', <org>, true)` per organization. Schema
  changes (DDL) are unaffected.

## Local setup

See the README's "Getting started".
