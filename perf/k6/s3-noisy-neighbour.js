// S3 Noisy neighbour (docs/scalability.md §7): one organization enqueues many large files, then ten
// small organizations upload one each. Pass: no small organization waits more than 2 minutes for
// its import to start (the per-organization cap of 2 running jobs keeps workers free for them).
//
// Env: BIG (JSON {org, files: [...]}), SMALL (JSON list of {org, file}); files mounted under /w.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE, auth, login } from './common.js';

const BIG = JSON.parse(__ENV.BIG);
const SMALL = JSON.parse(__ENV.SMALL);
const FILES = {};
for (const f of BIG.files) FILES[f] = open(f, 'b');
for (const s of SMALL) FILES[s.file] = open(s.file, 'b');

const smallWait = new Trend('small_org_wait_to_start_s');
const smallDone = new Trend('small_org_completion_s');

export const options = {
  scenarios: {
    big: { executor: 'shared-iterations', exec: 'big', vus: 5, iterations: BIG.files.length, maxDuration: '5m' },
    small: { executor: 'per-vu-iterations', exec: 'small', vus: SMALL.length, iterations: 1, startTime: '20s', maxDuration: '15m' },
  },
  thresholds: { small_org_wait_to_start_s: ['max<120'] },
  summaryTrendStats: ['min', 'med', 'avg', 'p(95)', 'max', 'count'],
};

export function setup() {
  return { token: login() };
}

function post(token, org, file) {
  return http.post(`${BASE}/api/v1/imports`, {
    file: http.file(FILES[file], file.split('/').pop(), 'text/csv'), sourceType: 'INTERNAL_LEDGER',
  }, auth(token, org));
}

export function big(data) {
  const res = post(data.token, BIG.org, BIG.files[__ITER % BIG.files.length]);
  check(res, { 'big upload 202': (x) => x.status === 202 });
}

export function small(data) {
  const s = SMALL[__VU - 1];
  const start = Date.now();
  const res = post(data.token, s.org, s.file);
  if (!check(res, { 'small upload 202': (x) => x.status === 202 })) return;
  const id = res.json('id');
  let started = false;
  for (let i = 0; i < 900; i++) {
    const st = http.get(`${BASE}/api/v1/imports/${id}`, auth(data.token, s.org)).json('status');
    if (!started && st !== 'RECEIVED') { smallWait.add((Date.now() - start) / 1000); started = true; }
    if (st === 'COMPLETED' || st === 'COMPLETED_WITH_ERRORS' || st === 'FAILED') { smallDone.add((Date.now() - start) / 1000); return; }
    sleep(0.5);
  }
}
