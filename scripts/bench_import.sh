#!/usr/bin/env bash
# Import and reconciliation benchmark (M9.6) against a locally running ReconFlow.
#
#   scripts/bench_import.sh <dataset-dir> [repetitions]
#
# <dataset-dir> holds ledger.csv, gateway.csv and expected.json from scripts/generate_dataset.py.
# Each repetition uses a fresh organization (the same file can't be imported twice into one: ADR
# 0005), created with SQL and joined by the seeded admin, then: upload the ledger and wait for it,
# upload the gateway file and wait, request a run for the dataset's period and wait. It prints one
# CSV line per repetition and checks every count against expected.json (exit 1 on a mismatch), so
# a benchmark is also a correctness check.
#
# Times: "wall" is measured here around the HTTP calls and polling (0.2 s resolution); "db" is the
# server's own started_at -> completed_at, which excludes queue wait and polling.
#
# Environment: BASE (http://localhost:8080), EMAIL (admin@acme.test), RECONFLOW_SEED_PASSWORD,
# PSQL (how to reach the database; default: psql inside the compose postgres container), ORG (reuse
# this organization instead of creating one per repetition; scripts/preload.sh uses it).
# Local only: it talks to the URL in BASE and to the compose database, nothing else.
set -euo pipefail

DATASET=${1:?usage: bench_import.sh <dataset-dir> [repetitions]}
REPS=${2:-1}
BASE=${BASE:-http://localhost:8080}
EMAIL=${EMAIL:-admin@acme.test}
PASSWORD=${RECONFLOW_SEED_PASSWORD:-reconflow-local-demo}
PSQL=${PSQL:-docker compose exec -T -e PGPASSWORD=${DB_PASSWORD:-} postgres psql -U ${DB_USER:-reconflow} -d paymentreconflow -qAt}

expected="$DATASET/expected.json"
period_start=$(jq -r .periodStart "$expected")
period_end=$(jq -r .periodEnd "$expected")
currency=$(jq -r .currency "$expected")
ledger_rows=$(jq -r .ledger.totalRows "$expected")
gateway_rows=$(jq -r .gateway.totalRows "$expected")

sql() { $PSQL -c "$1"; }
now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }
elapsed() { python3 -c "print(f'{$2 - $1:.2f}')"; }

token() {
  curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r .accessToken
}

new_org() {
  local org
  org=$(uuidgen | tr 'A-Z' 'a-z')
  sql "INSERT INTO organization (id, name) VALUES ('$org', 'bench $org');
       INSERT INTO membership (user_id, organization_id, role)
       SELECT id, '$org', 'ORG_ADMIN' FROM app_user WHERE email = '$EMAIL';" >/dev/null
  echo "$org"
}

# Polls a resource until its status is terminal; echoes the final status.
wait_for() {
  local url=$1 status
  while true; do
    status=$(curl -sf "$url" -H "Authorization: Bearer $TOKEN" -H "X-Organization-Id: $ORG" | jq -r .status)
    case "$status" in
      COMPLETED|COMPLETED_WITH_ERRORS|FAILED) echo "$status"; return ;;
    esac
    sleep 0.2
  done
}

upload() {
  local file=$1 source=$2
  curl -sf -X POST "$BASE/api/v1/imports" -H "Authorization: Bearer $TOKEN" -H "X-Organization-Id: $ORG" \
    -F "file=@$file;type=text/csv" -F "sourceType=$source" | jq -r .id
}

check() { # name, got, want
  if [ "$2" != "$3" ]; then echo "MISMATCH $1: got $2, expected $3" >&2; FAILED=1; fi
}

FAILED=0
echo "rep,ledger_rows,ledger_wall_s,ledger_db_s,ledger_rows_per_s,gateway_rows,gateway_wall_s,gateway_db_s,run_wall_s,run_db_s,records_per_s"
FIXED_ORG=${ORG:-}
# A caller running many imports passes its token (GIVEN_TOKEN): two logins per call would trip the
# per-IP login rate limit (M3.3f).
GIVEN_TOKEN=${TOKEN:-}
for rep in $(seq 1 "$REPS"); do
  ORG=${FIXED_ORG:-$(new_org)}
  TOKEN=${GIVEN_TOKEN:-$(token)}

  t0=$(now); batch=$(upload "$DATASET/ledger.csv" INTERNAL_LEDGER)
  st=$(wait_for "$BASE/api/v1/imports/$batch"); t1=$(now)
  check "ledger status" "$st" "COMPLETED_WITH_ERRORS"
  ledger_db=$(sql "SELECT round(extract(epoch FROM completed_at - started_at)::numeric, 2) FROM import_batch WHERE id = '$batch'")
  check "ledger counts" "$(sql "SELECT total_rows||'/'||valid_rows||'/'||error_rows FROM import_batch WHERE id = '$batch'")" \
    "$(jq -r '"\(.ledger.totalRows)/\(.ledger.validRows)/\(.ledger.errorRows)"' "$expected")"

  t2=$(now); gbatch=$(upload "$DATASET/gateway.csv" GATEWAY)
  st=$(wait_for "$BASE/api/v1/imports/$gbatch"); t3=$(now)
  check "gateway status" "$st" "COMPLETED_WITH_ERRORS"
  gateway_db=$(sql "SELECT round(extract(epoch FROM completed_at - started_at)::numeric, 2) FROM import_batch WHERE id = '$gbatch'")
  check "gateway counts" "$(sql "SELECT total_rows||'/'||valid_rows||'/'||error_rows FROM import_batch WHERE id = '$gbatch'")" \
    "$(jq -r '"\(.gateway.totalRows)/\(.gateway.validRows)/\(.gateway.errorRows)"' "$expected")"

  TOKEN=${GIVEN_TOKEN:-$(token)} # an access token lives 15 minutes
  t4=$(now)
  run=$(curl -sf -X POST "$BASE/api/v1/reconciliation-runs" -H "Authorization: Bearer $TOKEN" \
    -H "X-Organization-Id: $ORG" -H 'Content-Type: application/json' -H "Idempotency-Key: bench-$ORG-$period_start" \
    -d "{\"periodStart\":\"$period_start\",\"periodEnd\":\"$period_end\",\"currency\":\"$currency\"}" | jq -r .id)
  st=$(wait_for "$BASE/api/v1/reconciliation-runs/$run"); t5=$(now)
  check "run status" "$st" "COMPLETED"
  run_db=$(sql "SELECT round(extract(epoch FROM completed_at - started_at)::numeric, 2) FROM reconciliation_run WHERE id = '$run'")
  got=$(sql "SELECT json_build_object('matchedCount', matched_count, 'matchedAmount', matched_amount::text,
               'mismatchCount', mismatch_count, 'mismatchNetDifference', mismatch_net_difference::text,
               'unmatchedLeftCount', unmatched_left_count, 'unmatchedLeftAmount', unmatched_left_amount::text,
               'unmatchedRightCount', unmatched_right_count, 'unmatchedRightAmount', unmatched_right_amount::text,
               'duplicateCount', duplicate_count, 'currencyMismatchCount', currency_mismatch_count,
               'excludedCount', excluded_count) FROM reconciliation_run WHERE id = '$run'" | jq -S -c .)
  check "run summary" "$got" "$(jq -S -c .run "$expected")"

  lw=$(elapsed "$t0" "$t1"); gw=$(elapsed "$t2" "$t3"); rw=$(elapsed "$t4" "$t5")
  echo "$rep,$ledger_rows,$lw,$ledger_db,$(python3 -c "print(round($ledger_rows / $ledger_db))"),$gateway_rows,$gw,$gateway_db,$rw,$run_db,$(python3 -c "print(round(($ledger_rows + $gateway_rows) / max($run_db, 0.01)))")"
done
[ "$FAILED" = 0 ] || { echo "Counts differ from expected.json" >&2; exit 1; }
echo "all counts match expected.json" >&2
