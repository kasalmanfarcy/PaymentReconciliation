# 0013. Build authentication in-house for v1, delegate to an identity provider later

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M3.3, M10.1

## Context

ReconFlow needs login, short-lived access tokens, refresh, logout, lockout and admin-created users
(TASKS.md M3.3). A production system would get all of this from an identity provider (Keycloak,
Auth0, Cognito, Entra ID). This project is also a learning exercise: the mechanics behind an IdP
(token signing, refresh rotation and reuse detection, enumeration-safe login, lockout races) are
exactly what interviews probe and what an IdP hides. The full design is
[plans/authentication.plan](../../plans/authentication.plan).

## Decision

We will build authentication in-house for v1, on Spring Security, and replace the token-issuing
half with Keycloak in M10.1.

- **We write the flows, never the cryptography.** Passwords use Spring Security's
  `DelegatingPasswordEncoder` with bcrypt; JWTs are signed and verified by Nimbus through
  `NimbusJwtEncoder` / `NimbusJwtDecoder` (RS256); refresh tokens come from `SecureRandom` and are
  stored as SHA-256 hashes. No JWT is ever parsed by hand.
- **One seam.** The app validates its own JWTs as an OAuth2 resource server, and business code reads
  identity only through `CurrentUser` / `TenantContext`. M10.1 swaps the `JwtDecoder` for an
  `issuer-uri` and deletes `security.auth` and `security.token`; nothing outside `security`
  changes.
- **bcrypt cost factor 12.** Measured on the development machine (Apple M5, 10 cores, JDK 25,
  `BCryptPasswordEncoder.encode`, average of 20 hashes after a warm-up): **cost 10 = 52 ms,
  cost 12 = 208 ms**. 12 is the highest cost under the ~250 ms target; 13 would be ~420 ms per
  login. Re-measure on the production hardware before launch: the argument depends on that number,
  and so does the timing equalisation of the dummy hash for unknown emails.
- **Hashes upgrade themselves.** Stored hashes carry their algorithm and cost (`{bcrypt}$2a$12$…`).
  On every successful login, `upgradeEncoding(hash)` is checked and the password is rehashed if it
  returns true, so raising the cost (or changing algorithm) never needs a mass password reset.

## Consequences

- We own the security of the login path until M10.1: lockout, rate limiting, enumeration-safe
  errors and refresh-token reuse detection all have tests (M3.3f, M3.3j), and the threat table in
  authentication.plan §8 lists what is and isn't covered.
- No email, so no self-service password reset: an org admin resetting the password is the only
  recovery path in v1.
- The login rate limit is in memory, per instance: two API instances allow twice the configured
  rate.
- Moving to Keycloak drops `refresh_token` and `app_user.password_hash` one release after cutover,
  keeping a rollback path (authentication.plan §9).

## Alternatives considered

- **Keycloak from day one.** The production answer, and where M10.1 ends up. Rejected for v1 only
  because it would hide the mechanics this project exists to practise, and adds a container and
  realm configuration before there is any business code.
- **A managed IdP (Auth0, Cognito).** Same trade-off plus an external account and network
  dependency for tests; the project runs entirely on Docker and Maven downloads.
- **Server-side sessions instead of JWTs.** Simpler revocation, but API instances become stateful
  (sticky sessions or a shared session store), and the M10 migration to an IdP's tokens would
  change every request path instead of one decoder.
