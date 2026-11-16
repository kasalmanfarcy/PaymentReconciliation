// Shared helpers for the k6 scenarios (docs/scalability.md §7, phase-M9 §M9.6).
// Run against a LOCAL ReconFlow only, e.g.:
//   docker run --rm -i -v "$PWD:/w" -w /w -e BASE=http://host.lima.internal:8080 -e ORG=<uuid> \
//     grafana/k6:1.3.0 run perf/k6/s1-steady-browsing.js
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
