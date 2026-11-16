// S1 Steady browsing (docs/scalability.md §7): 100 users, 2 requests per minute each, with the
// realistic mix 60% exception list, 25% exception detail, 10% transaction search, 5% report.
// Pass: read p95 < 300 ms (SLO §6), error rate < 0.1%.
//
// Env: ORG (an organization with a completed run), RUN (that run's id), VUS (100), DURATION (10m:
// access tokens live 15 minutes and the scenario logs in once), PACE (30 s between a user's requests).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, auth, login, pick } from './common.js';

const ORG = __ENV.ORG;
const RUN = __ENV.RUN;
const PACE = Number(__ENV.PACE || 30);

export const options = {
  scenarios: {
    browsing: { executor: 'constant-vus', vus: Number(__ENV.VUS || 100), duration: __ENV.DURATION || '10m' },
  },
  thresholds: {
    'http_req_duration{kind:read}': ['p(95)<300'],
    'http_req_failed{kind:read}': ['rate<0.001'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
  const token = login();
  const page = http.get(`${BASE}/api/v1/exceptions?size=200`, auth(token, ORG)).json('content');
  return { token, exceptionIds: page.map((e) => e.id), references: page.map((e) => e.reference) };
}

export default function (data) {
  // Spread the users' first requests over one pace interval, as real users don't arrive in step.
  if (__ITER === 0) sleep(Math.random() * PACE);
  const p = auth(data.token, ORG);
  p.tags = { kind: 'read' };
  const r = Math.random();
  let res;
  if (r < 0.6) {
    res = http.get(`${BASE}/api/v1/exceptions?page=${Math.floor(Math.random() * 5)}&size=20`, p);
  } else if (r < 0.85) {
    res = http.get(`${BASE}/api/v1/exceptions/${pick(data.exceptionIds)}`, p);
  } else if (r < 0.95) {
    res = http.get(`${BASE}/api/v1/transactions?reference=${encodeURIComponent(pick(data.references))}`, p);
  } else {
    res = http.get(`${BASE}/api/v1/reports/reconciliation?runId=${RUN}`, p);
  }
  check(res, { 'status 200': (x) => x.status === 200 });
  sleep(PACE);
}
