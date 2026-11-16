// Shared helpers for the k6 scenarios (docs/scalability.md §7, phase-M9 §M9.6).
// Run against a LOCAL ReconFlow only, with k6 installed on the host, e.g.:
//   k6 run -e ORG=<uuid> -e RUN=<uuid> perf/k6/s1-steady-browsing.js
// Not the grafana/k6 image under Colima: its scenario clock advanced 8 minutes in 52 wall-clock
// minutes while VUs slept (docs/benchmarks.md §3), so durations and latencies were meaningless.
import http from 'k6/http';

export const BASE = __ENV.BASE || 'http://localhost:8080';
export const EMAIL = __ENV.EMAIL || 'admin@acme.test';
export const PASSWORD = __ENV.PASSWORD || 'reconflow-local-demo';

/** One login in setup(): the per-IP login rate limit (M3.3f) would refuse one per virtual user. */
export function login() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { kind: 'login' },
  });
  if (res.status !== 200) {
    throw new Error(`login failed: ${res.status} ${res.body}`);
  }
  return res.json('accessToken');
}

export function auth(token, org, extra = {}) {
  return { headers: Object.assign({ Authorization: `Bearer ${token}`, 'X-Organization-Id': org }, extra) };
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
