// Shared helpers for the k6 scenarios (docs/scalability.md §7, phase-M9 §M9.6).
// Run against a LOCAL ReconFlow only, with k6 installed on the host, e.g.:
//   k6 run -e ORG=<uuid> -e RUN=<uuid> perf/k6/s1-steady-browsing.js
// Not the grafana/k6 image under Colima: its scenario clock advanced 8 minutes in 52 wall-clock
// minutes while VUs slept (docs/benchmarks.md §3), so durations and latencies were meaningless.
import http from 'k6/http';

export const BASE = __ENV.BASE || 'http://localhost:8080';
export const EMAIL = __ENV.EMAIL || 'admin@acme.test';
export const PASSWORD = __ENV.PASSWORD || 'reconflow-local-demo';

export const KEYCLOAK = __ENV.KEYCLOAK || 'http://localhost:8180';

/**
 * One token in setup(), from Keycloak (M10.1) with the local realm's demo client. One per virtual
 * user would trip Keycloak's brute-force guard and measure Keycloak, not ReconFlow.
 */
export function login() {
  const res = http.post(
    `${KEYCLOAK}/realms/reconflow/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'reconflow-demo', username: EMAIL, password: PASSWORD },
    { tags: { kind: 'login' } },
  );
  if (res.status !== 200) {
    throw new Error(`login failed: ${res.status} ${res.body}`);
  }
  return res.json('access_token');
}

export function auth(token, org, extra = {}) {
  return { headers: Object.assign({ Authorization: `Bearer ${token}`, 'X-Organization-Id': org }, extra) };
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
