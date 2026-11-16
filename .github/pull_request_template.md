## What and why

<!-- What does this change do, and why is it needed? -->

## Task

<!-- e.g. M5.11. Tick it in TASKS.md in this PR if it completes the task. -->

## Definition of done

- [ ] `./mvnw verify` passes locally
- [ ] Money is `BigDecimal` / `NUMERIC`, compared with `compareTo`
- [ ] Every tenant-owned query is scoped by `organizationId`
- [ ] New endpoints have cross-tenant and authorization tests
- [ ] State changes go through the central transition logic and emit an audit event
- [ ] Schema changes are a new Flyway migration; applied migrations are never edited
- [ ] List endpoints paginate
- [ ] Docs in `docs/` are updated when behaviour changes
