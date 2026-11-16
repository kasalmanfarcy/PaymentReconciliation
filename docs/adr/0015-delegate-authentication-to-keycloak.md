# 0015. Delegate authentication to Keycloak

- **Status:** Accepted (supersedes [ADR 0013](0013-build-authentication-in-house.md))
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M10.1

## Context

ADR 0013 built authentication in-house for v1 on purpose: token signing, refresh rotation with reuse
detection, enumeration-safe login and lockout races are what an identity provider hides and what
interviews probe. M3.3 delivered it: `security.auth` and `security.token` (about 1,370 lines), the
RS256 key and password-encoder configuration (about 150 more), 42 test methods in 8 classes, plus
the login and refresh cases in `SecurityAuditIT`. The authentication plan's two review passes
(authentication.plan §12–§13) caught real defects before they shipped: a foreign-key violation in
refresh rotation, a timing side channel in the lockout path, a lost update on the failure counter, a
counter never reset after a lock expired, and a reuse handler that would have been rolled back with
its own transaction. That is the evidence of how easy this code is to get subtly wrong, and how much
of it there is to keep right.

Running it in production would mean owning password storage, brute-force defence, MFA, account
recovery (v1 has no email, so an admin reset is the only path) and every future CVE in that code.

## Options

1. **Keep the in-house implementation.** No new moving part; all the liability above, forever.
2. **Self-hosted Keycloak.** Open source, OIDC/OAuth 2.1, runs in Docker locally and in tests.
3. **A managed IdP** (Auth0, Cognito, Entra ID). Nothing to operate; per-active-user pricing, vendor
   lock-in, and tests that need an external account and network.

## What Keycloak gives us

MFA (TOTP, WebAuthn), SSO and social or enterprise login, password reset by email once email exists,
brute-force detection, password policies, refresh-token rotation with reuse revocation, login and
admin event logs, an admin console, key rotation behind a JWKS, standards compliance tested by many
users, and security fixes from a dedicated team.

| M3.3 behaviour (deleted test) | Keycloak setting that provides it now (`infra/keycloak/realm-reconflow.json`) | Proven by |
|---|---|---|
| bcrypt cost 12, upgrade on login (`PasswordStorageTest`) | Keycloak's own hashing (PBKDF2/Argon2 per version), upgraded on login | Keycloak |
| Password policy: 12–64 characters, not the username (`PasswordStorageTest`) | `passwordPolicy: length(12) and maxLength(64) and notUsername` | realm file |
| Lockout after 5 failures for 15 min, enumeration-safe errors (`BruteForceProtectionIT`, `LoginIT`) | `bruteForceProtected`, `failureFactor: 5`, `waitIncrementSeconds`/`maxFailureWaitSeconds: 900`; identical `invalid_grant` errors | `KeycloakIT.fiveWrongPasswordsLockTheAccountTemporarily` |
| Per-IP login rate limit (`LoginRateLimiterTest`) | Brute-force detection's quick-login check; per-IP limits belong at the edge (M11) | — (accepted, docs/security.md) |
| 15-minute access token; rotating refresh token, reuse revokes the family, 30-day cap (`SessionFlowIT`, `RefreshTokenServiceIT`) | `accessTokenLifespan: 900`, `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0`, `ssoSessionIdleTimeout` 7 d, `ssoSessionMaxLifespan` 30 d | realm file |
| Temporary password must be changed first (`password-change-required`) | "Update Password" required action: no token until it's changed | `KeycloakIT.aMemberCreatedThroughTheApiExistsInKeycloakAndMustChooseAPassword` |
| Signed tokens, `iss`/`aud`/`exp` checked, ephemeral local keys (`JwtKeyConfigTest`, `AuthenticationChecklistIT`) | Keycloak's realm keys behind its JWKS; Boot's `issuer-uri` decoder with `audiences: reconflow-api` | `ResourceServerIT`, `KeycloakIT.aTokenIssuedForAnotherAudienceIs401` |
| Login, failure, lockout, logout, reuse audit events (`SecurityAuditIT`) | Login events and admin events enabled | Keycloak's event log |
| Admin reset and disable revoke sessions | Admin API `reset-password` (temporary) and `enabled: false`, each followed by `logout` | `MemberManagementIT`, `KeycloakIT` |

## What each costs

- **In-house:** the security liability and maintenance above; no MFA, no self-service recovery.
- **Keycloak:** another stateful service with its own database in production, upgrades every few
  months (pinned `26.8.2`, Dependabot watches the compose tag), roughly 512 MB–1 GB of memory, a
  realm configuration to keep in git, and a learning curve (two surprises below). Locally it is one
  container that starts in about 6 s.
- **Managed IdP:** money per active user and lock-in, no operations; tests would need a network
  account, which this project avoids.

## What changed outside `security/`

The design predicted that only `security/` would change (authentication.plan §9). The honest list:

- **Configuration and infrastructure** (expected): `application*.yml` (issuer, audience, Admin API),
  `compose.yaml` (the Keycloak service), `.env.example`, the realm file, `V12__app_user_external_subject.sql`
  and the contract script `infra/db/contract/drop_in_house_auth.sql`.
- **Scripts and load tests** (expected): `scripts/*.sh` and `perf/k6/common.js` get tokens from
  Keycloak instead of `/auth/login`.
- **`audit.AuditAction`**: a new `USER_LINKED` constant. Not a leak: a new kind of event, added where
  every event type lives.
- **`shared.error.ProblemCodes`**: `identity-provider-unavailable` added; `invalid-credentials`,
  `password-policy` and `password-change-required` removed with the endpoints that produced them.
- **No business module changed.** `ingestion`, `ledger`, `reconciliation`, `exceptions`, `reporting`,
  `organization` and `audit`'s service are untouched: they only ever saw `CurrentUser` and
  `TenantContext`, whose implementation is unchanged. `CurrentUser.id()` is still ReconFlow's UUID,
  not Keycloak's `sub`.
- **Tests:** the cross-tenant suites (`CrossTenantIT`, `ExceptionCrossTenantIT`, the `*CrossTenantCases`)
  and `AuthorizationMatrixIT` pass without edits. The shared test support changed underneath them:
  tokens are signed by a stand-in OIDC issuer (`support.TestIssuer`) and validated by the real
  `issuer-uri` decoder, and fixture users are linked by `external_subject`.
- **Member API response:** `mustChangePassword` was removed from `/members` responses; whether a
  temporary password is pending is Keycloak's to know now.

## Decision

Keycloak issues every token; ReconFlow is only a resource server. Keycloak answers *who are you*;
ReconFlow keeps answering *what may you do, in which organization*: no roles, groups or organizations
in Keycloak or in tokens, because permissions in a token would be stale until it expires.

- **Linking:** by `sub`, in `app_user.external_subject` (unique). Email is used once, to link a
  pre-provisioned user on first login, and only when the token says `email_verified: true`; an
  unverified email would be an account takeover. A user from before the cutover gets a Keycloak
  account when an admin resets their password (the plan's "temporary password plus Update Password"
  migration; Keycloak can't verify our bcrypt hashes as-is).
- **User management** stays in ReconFlow's API: `POST /members` creates the Keycloak account through
  its Admin REST API (a confidential service-account client with only `manage-users`), then the
  `app_user` row. No transaction spans both systems, so a rollback of the database half triggers a
  compensating delete of the Keycloak account (`AccountProvisioning`).
- **Admin API client:** five calls through Spring's `RestClient` rather than `keycloak-admin-client`,
  which would bring RESTEasy and a second Jackson onto the Boot 4 classpath for them.
- **Cutover:** switched directly; no dual-issuer period. For real users you would accept both issuers
  for one access-token lifetime with `JwtIssuerAuthenticationManagerResolver.fromTrustedIssuers`, which
  reads the unverified `iss` only to choose the decoder and then verifies fully.
- **Expand/contract:** V12 only adds `external_subject` and relaxes `password_hash`. Dropping
  `refresh_token` and the password/lockout columns is written but not applied
  (`infra/db/contract/drop_in_house_auth.sql`), to ship one release later, so rolling back to the
  in-house login stays possible until then.

Two surprises worth remembering: Keycloak's default access token has `aud: account`, so without the
realm's audience mapper every request is `401`; and Keycloak 26.6+ only accepts Admin API tokens
that carry its admin audience, so the service-account client needs the built-in `kc-admin-api`
scope.

## Consequences

- No password, password hash, refresh token or signing key is handled by ReconFlow any more; the
  in-memory, per-instance login rate limit is gone with the login endpoint.
- The API needs Keycloak reachable for the first request after start (JWKS fetch) and for user
  management (`503 identity-provider-unavailable` otherwise, with nothing changed). Token validation
  afterwards is local (cached keys).
- `prod` refuses to start without `OIDC_ISSUER_URI`, `KEYCLOAK_BASE_URL` and
  `KEYCLOAK_ADMIN_CLIENT_SECRET`.
- The `reconflow-demo` client's password grant exists in the local realm only; it is removed in
  OAuth 2.1 and must never be enabled in a deployed realm.
- Deployed Keycloak runs `start` with PostgreSQL and TLS, and the issuer URL must be identical for
  browsers and the API (`KC_HOSTNAME`), or every token fails `iss` validation.
