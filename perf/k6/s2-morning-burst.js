// S2 Morning burst (docs/scalability.md §7): large and medium uploads within two minutes while S1's
// browsing runs. Pass: every import completes within 5 minutes; browsing p95 stays under 300 ms
// (the API and the worker don't starve each other).
//
// Env: ORG/RUN (browsing, as S1), UPLOADS: a JSON list of {org, file} with distinct files (a file can
// be imported once per organization: ADR 0005), mounted under /w; VUS, DURATION, PACE as S1.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { BASE, auth, login } from './common.js';
import browse from './s1-steady-browsing.js';

const UPLOADS = JSON.parse(__ENV.UPLOADS || '[]');
const FILES = {};
for (const u of UPLOADS) FILES[u.file] = open(u.file, 'b');

const importSeconds = new Trend('import_completion_s');
const importsFailed = new Counter('imports_not_completed');

export const options = {
  scenarios: {
    browsing: { executor: 'constant-vus', exec: 'browsing', vus: Number(__ENV.VUS || 100), duration: __ENV.DURATION || '8m' },
    uploads: { executor: 'per-vu-iterations', exec: 'upload', vus: UPLOADS.length || 1, iterations: 1,
               startTime: '30s', maxDuration: '7m' },
  },
  thresholds: {
    'http_req_duration{kind:read}': ['p(95)<300'],
    import_completion_s: ['max<300'],
    imports_not_completed: ['count<1'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
  const token = login();
  const page = http.get(`${BASE}/api/v1/exceptions?size=200`, auth(token, __ENV.ORG)).json('content');
  return { token, exceptionIds: page.map((e) => e.id), references: page.map((e) => e.reference) };
}

export function browsing(data) {
  browse(data);
}

export function upload(data) {
  const u = UPLOADS[__VU - 1];
  sleep(Math.random() * 90); // all uploads arrive within two minutes
  const start = Date.now();
  const p = auth(data.token, u.org);
  p.tags = { kind: 'upload' };
  const res = http.post(`${BASE}/api/v1/imports`, {
    file: http.file(FILES[u.file], u.file.split('/').pop(), 'text/csv'),
    sourceType: u.source || 'INTERNAL_LEDGER',
  }, p);
  if (!check(res, { 'upload 202': (x) => x.status === 202 })) { importsFailed.add(1); return; }
  const id = res.json('id');
  const poll = auth(data.token, u.org);
  poll.tags = { kind: 'poll' };
  for (let i = 0; i < 600; i++) {
    const s = http.get(`${BASE}/api/v1/imports/${id}`, poll).json('status');
    if (s === 'COMPLETED' || s === 'COMPLETED_WITH_ERRORS') { importSeconds.add((Date.now() - start) / 1000); return; }
    if (s === 'FAILED') break;
    sleep(1);
  }
  importsFailed.add(1);
}
