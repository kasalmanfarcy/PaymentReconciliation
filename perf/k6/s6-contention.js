// S6 Contention (docs/scalability.md §7): 50 users resolving the same 10 exceptions at once.
// Pass: exactly one 200 per exception, every other attempt 409 (stale-version or invalid-transition),
// no other status: optimistic locking (ADR 0007) loses no update and lets no second resolve through.
//
// Env: ORG (an organization with at least 10 open cases).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { BASE, auth, login } from './common.js';

const ok = new Counter('resolve_200');
const conflict = new Counter('resolve_409');
const other = new Counter('resolve_other');

export const options = {
  scenarios: { contention: { executor: 'per-vu-iterations', vus: 50, iterations: 1, maxDuration: '2m' } },
  thresholds: { resolve_200: ['count==10'], resolve_other: ['count==0'] },
};

export function setup() {
  const token = login();
  const ids = http.get(`${BASE}/api/v1/exceptions?size=10&sort=createdAt,asc`, auth(token, __ENV.ORG)).json('content').map((e) => e.id);
  if (ids.length < 10) throw new Error('need 10 open cases');
  return { token, ids };
}

export default function (data) {
  const id = data.ids[(exec.vu.idInTest - 1) % 10]; // 5 users per case
  const read = http.get(`${BASE}/api/v1/exceptions/${id}`, auth(data.token, __ENV.ORG));
  const res = http.post(`${BASE}/api/v1/exceptions/${id}/resolve`,
    JSON.stringify({ resolutionCode: 'TIMING_DIFFERENCE', note: `k6 VU ${exec.vu.idInTest}` }),
    auth(data.token, __ENV.ORG, { 'Content-Type': 'application/json', 'If-Match': read.headers['Etag'] }));
  if (res.status === 200) ok.add(1);
  else if (res.status === 409) conflict.add(1);
  else other.add(1);
  check(res, { '200 or 409': (x) => x.status === 200 || x.status === 409 });
}
