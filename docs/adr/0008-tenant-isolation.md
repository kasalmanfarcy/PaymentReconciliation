# 0008. Tenant isolation: shared schema, organization_id everywhere, RLS as a second layer

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M3.4, M3.5, M3.6, M3.9, M10.7

## Context

The README asked for the tenant enforcement mechanism. ReconFlow holds many businesses' payment data in one system; a user of organization A must never read or change organization B's records, including by guessing an ID. Target scale: about 100 organizations, very uneven in size (scalability.md §1). Hiding rows in a UI is not access control.

## Decision

We will use a **shared database and shared schema**, with isolation enforced in layers:

1. **Data:** every tenant-owned table carries `organization_id`, including child tables, and tenant queries' indexes lead with it.
2. **Request context:** `X-Organization-Id` selects the organization. `TenantContextFilter` checks membership on every request and fills a request-scoped `TenantContext` (an interface in `shared`, implemented in `security`). Business code reads the tenant only from `TenantContext`, never from a request parameter.
3. **Application queries:** every repository method takes `organizationId` and filters by it. Lookups are `WHERE id = :id AND organization_id = :org`.
4. **Database row-level security (defense in depth):** an RLS policy on every tenant-owned table, `USING (organization_id = current_setting('app.org_id')::uuid)`, with the setting applied per transaction (`set_config('app.org_id', :org, true)`, the function form of `SET LOCAL`, so it can't leak to the next transaction on a pooled connection). The application connects as a role that isn't the table owner or a superuser (M10.7), because those bypass RLS.
5. **Response:** another tenant's IDs return **`404`**, never `403`, so IDs can't be probed for existence.
6. **Tests:** a cross-tenant suite asserts, for every endpoint, that organization B's user can't read, list, update or resolve organization A's resources (M3.9), with a completeness check that fails when a new endpoint lacks a case.

## Consequences

- One schema, one migration run, one connection pool for all tenants.
- A forgotten `WHERE organization_id` in a query returns nothing (RLS) instead of another tenant's data. Two independent mechanisms have to fail together for a leak.
- Every transaction must set `app.org_id`; forgetting it makes queries return no rows, which fails loudly in tests rather than leaking. Background jobs set it from the job's `organization_id`.
- Platform-admin operations that legitimately span organizations need an explicit, narrow path (`TenantScope`) and are audited.
- Noisy neighbours share the database; mitigated by per-organization job limits (ADR 0004) and, at much larger scale, by moving a big tenant to its own database (scalability.md §8).

## Alternatives considered

- **Schema per tenant.** Strong separation, but every migration runs 100 times, connection pools and catalog size grow with tenants, and cross-tenant operations (platform admin) get awkward. Not justified at 100 tenants. Rejected.
- **Database per tenant.** The strongest isolation and the easiest per-tenant backup, but the connection count and operational cost explode. Reconsidered only for a tenant with data-residency or contractual needs.
- **Hibernate `@TenantId` / filters alone.** Convenient, but only covers queries Hibernate generates; one native or JDBC query (and the engine is mostly JDBC) without the filter leaks data. Rejected as the *only* mechanism.
- **Application filtering only, no RLS.** Correct if every query is right. The point of RLS is that one wrong query isn't a breach. Rejected.
