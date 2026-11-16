# Runbook: deploy ReconFlow (M11.4, M11.5)

**Target:** one Linux VM running Docker Compose ([deploy/compose.prod.yaml](../../deploy/compose.prod.yaml)),
in front of a **managed PostgreSQL 16** and an **S3-compatible bucket**. Traefik terminates TLS
(Let's Encrypt), caps request bodies and rate-limits per client IP; Keycloak runs in production mode
on its own database. Images come from GHCR, built by CI ([ci.yml](../../.github/workflows/ci.yml)
`publish-image`) and tagged `sha-<commit>`.

**Status:** prepared and rehearsed locally end to end ([§9](#9-local-rehearsal)); not yet deployed
to a host. Every step marked **(you)** needs an account or a machine outside this repository.

---

## 1. Why this shape

| Need | How it's met |
|---|---|
| api + worker as separate processes | two services from one image (`prod,api`, `prod,worker`) |
| Migrations once per deploy, with their own credentials | `deploy.sh` stage 2 runs `prod,migrate`; only that container gets `DB_MIGRATOR_PASSWORD` (M11.5) |
| Database backups and PITR not on us | managed PostgreSQL 16; the VM holds no state but certificates |
| TLS, upload cap, per-IP limits, forwarded headers | Traefik ([routes.yml](../../deploy/traefik/dynamic/routes.yml)); the app trusts `X-Forwarded-*` only from internal addresses (`server.forward-headers-strategy: native`) |
| Keycloak without the password grant | [realm-reconflow-prod.json](../../infra/keycloak/realm-reconflow-prod.json): no `reconflow-demo`; scripts log in with authorization code + PKCE (`reconflow-cli`) |

A PaaS (Render, Fly.io) was the plan's first suggestion. The VM was chosen because the exact files
can be rehearsed on a laptop, and because the edge controls M10 left open (body cap and per-IP
limits in front of the app and Keycloak) need a proxy a PaaS doesn't give you. The cost: you patch the
VM's OS and Docker yourself. A managed IdP instead of self-hosted Keycloak needs no code change, only
`OIDC_ISSUER_URI`, the JWKS URL and an `aud` of `reconflow-api` ([ADR 0015](../adr/0015-delegate-authentication-to-keycloak.md)).

## 2. What you need (you)

- A VM: 2 vCPU, **4 GB RAM** (limits: Keycloak 1 GB, api 768 MB, worker 768 MB, Traefik 128 MB, plus
  the OS), Docker Engine with the Compose plugin, ports 80 and 443 open, SSH by key only.
- Two DNS names pointing at it, e.g. `api.example.com` and `auth.example.com`.
- A managed **PostgreSQL 16** (pin the major version), reachable from the VM over TLS.
- An S3-compatible bucket (AWS S3, Cloudflare R2, Backblaze B2): public access blocked, one access
  key scoped to that bucket only.
- The GHCR package `ghcr.io/kasalmanfarcy/reconflow` (pushed by CI after the first merge to `main`).
  Either make it public (package settings) or `docker login ghcr.io` on the VM with a read-only token.

## 3. One-time setup

1. **Database roles and databases**, as the managed server's admin user, from a checkout:

   ```bash
   psql "host=<db-host> dbname=paymentreconflow user=<admin> sslmode=verify-full" \
     -v migrator_pw="$(openssl rand -base64 24)" -v app_pw="$(openssl rand -base64 24)" \
     -f infra/db/bootstrap-roles.sql            # reconflow_migrator owns the schema, reconflow_app DML only
   psql "host=<db-host> dbname=postgres user=<admin> sslmode=verify-full" \
     -v kc_pw="$(openssl rand -base64 24)" -f infra/db/bootstrap-keycloak.sql   # database + role keycloak
   ```

   Keep the three passwords for step 3. (Create the `paymentreconflow` database first if the provider
   didn't.) Never put the admin user's credentials on the VM.
2. **The VM:** `sudo git clone https://github.com/kasalmanfarcy/PaymentReconciliation /opt/reconflow`
   and give the deploy user ownership.
3. **Secrets:** `cp deploy/.env.prod.example deploy/.env && chmod 600 deploy/.env`, then fill it in
   (each line is commented). Generate secrets with `openssl rand -base64 32`. `API_PROFILES=prod,api,demo`
   for a public demo, `prod,api` otherwise.
4. **First deploy** (§4) with the newest `sha-<commit>` tag.
5. **Keycloak admin:** the bootstrap admin is temporary. Replace it with a named admin, inside the
   container (the console isn't routed publicly):

   ```bash
   docker compose -p reconflow-prod --env-file deploy/.env -f deploy/compose.prod.yaml exec keycloak bash -c '
     kc=/opt/keycloak/bin/kcadm.sh
     $kc config credentials --server http://localhost:8080 --realm master --user admin --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"
     $kc create users -r master -s username=<you> -s enabled=true
     $kc set-password -r master --username <you> --new-password "<strong password>"
     $kc add-roles -r master --uusername <you> --rolename admin
     $kc delete users/$($kc get users -r master -q username=admin --fields id --format csv --noquotes) -r master'
   ```

6. **First platform admin** (not the demo): set `RECONFLOW_BOOTSTRAP_ADMIN_EMAIL`, create that user in
   the `reconflow` realm with a verified email (`kcadm.sh create users -r reconflow ...`), deploy,
   log in once, then remove the variable.

## 4. Deploy

```bash
cd /opt/reconflow && git fetch && git checkout --detach <commit>
deploy/deploy.sh sha-<commit>
```

Stages, each logged with a timestamp: **pull** → **migrate** (`prod,migrate`, exit code checked;
a failure stops the deploy before anything new starts) → **edge** (Traefik, Keycloak) → **worker** →
**api** (done when `/actuator/health/readiness` is UP). Then run the smoke test from your laptop:

```bash
BASE_URL=https://api.example.com KEYCLOAK_URL=https://auth.example.com DEMO_PASSWORD=... scripts/demo.sh --no-start
```

**Continuous deploy (you):** [deploy.yml](../../.github/workflows/deploy.yml) does the same after CI
succeeds on `main`. To switch it on: create the environment `production` (Settings → Environments;
add yourself as required reviewer), its secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY` (a key
only for this), `DEPLOY_KNOWN_HOSTS` (`ssh-keyscan <host>`), `DEMO_PASSWORD`, the repository variables
`API_HOST`, `AUTH_HOST`, and finally `DEPLOY_ENABLED=true`.

## 5. Roll back

Deploy the previous tag: `deploy/deploy.sh sha-<previous commit>` (its tag is in
`deploy/.deployed-tag.reconflow-prod` before you deploy the new one). This is safe because
migrations are expand/contract ([CONTRIBUTING.md](../../CONTRIBUTING.md#migrations)): the previous
release works on the newer schema, and its Flyway ignores migrations newer than itself. There is no
"down" migration. If data is wrong, not just code, restore with PITR
([restore-drill.md](restore-drill.md)).

**Pending contract step:** `infra/db/contract/drop_in_house_auth.sql` (M10.1) becomes the next
numbered migration only in the release *after* the Keycloak switch is deployed and stable; until
then a rollback to the in-house-auth release must stay possible.

## 6. Downtime

The api container is recreated in place: during its restart (the rehearsal measured 7 s from
"recreate" to healthy) Traefik answers 502 (no ready backend). The worker's jobs are handed back at
the next chunk boundary on `SIGTERM` (graceful shutdown, benchmarks §4) and resumed by the new one.
Removing the gap means two api containers updated one at a time (Traefik's health check already routes
only to ready instances), which doubles the api's memory. Not done for a demo.

## 7. Operate

| Task | Command (from `/opt/reconflow`, `C="docker compose -p reconflow-prod --env-file deploy/.env -f deploy/compose.prod.yaml"`) |
|---|---|
| Status | `$C ps` |
| Logs (JSON, ECS) | `$C logs -f api` / `worker` / `keycloak` / `traefik` |
| Readiness | `$C exec api wget -qO- http://127.0.0.1:8081/actuator/health/readiness` |
| Metrics | `$C exec api wget -qO- http://127.0.0.1:8081/actuator/prometheus` (never routed publicly) |
| Rotate a DB password | re-run `bootstrap-roles.sql` with the new one, update `deploy/.env`, `deploy/deploy.sh <current tag>` |
| Patch the VM | OS updates monthly; base-image bumps arrive as Dependabot PRs and ship with the next deploy |

Alert on container restarts of the worker as well as the api: a crash-looping worker serves no
traffic, so no health check in front of users would notice it.

## 8. Checklist after the first deploy (you)

- [ ] `https://api.../api/v1/members` → `401` with `Strict-Transport-Security`
- [ ] `http://api...` → `301` to HTTPS
- [ ] `https://auth.../admin/` → `404`; `https://api.../actuator/prometheus` → `401`
- [ ] password grant on the deployed realm → `invalid_client` (no `reconflow-demo` client)
- [ ] `scripts/demo.sh --no-start` against the public URLs → "Demo complete"
- [ ] during a second deploy, loop `curl -s -o /dev/null -w '%{http_code}\n' https://api.../api/v1/members`
      every 0.5 s (expect `401`) and count the `502`s: that is the restart gap of §6

## 9. Local rehearsal

The same compose file and script, with PostgreSQL and MinIO in containers standing in for the managed
services and a throwaway CA instead of Let's Encrypt ([compose.local.yaml](../../deploy/compose.local.yaml)).
Nothing leaves the machine.

```bash
scripts/local_tls.sh && cp deploy/.env.local.example deploy/.env.local
docker build -t reconflow:local .
RECONFLOW_LOCAL=1 deploy/deploy.sh local
CURL_CA=deploy/local/certs/ca.pem BASE_URL=https://api.reconflow.localhost:8443 \
  KEYCLOAK_URL=https://auth.reconflow.localhost:8443 DEMO_PASSWORD=rehearsal-demo-password scripts/demo.sh --no-start
docker compose -p reconflow-rehearsal --env-file deploy/.env.local \
  -f deploy/compose.prod.yaml -f deploy/compose.local.yaml down -v
```

Rehearsed on 2026-11-16 (Colima): all five stages passed (migrate 4 s, api ready 7 s after its
stage began); through Traefik with certificate verification: `401` with HSTS and the CSP, HTTP → `301`,
`/admin/` → `404`, `/actuator/prometheus` → `401`, password grant → `invalid_client`, PKCE login → a
token, `POST /members` created the Keycloak account over the internal Admin API (`201`), a 54 MB
upload → `413` from Traefik (the API never saw it), the 21st token request in a burst from one IP →
`429`; then the demo script (M11.9) end to end.
