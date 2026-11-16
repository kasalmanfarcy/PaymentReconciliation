#!/usr/bin/env bash
# Scenario S5 (docs/scalability.md §7) and the M9.11 shutdown check, against a LOCAL ReconFlow.
#
#   APP_PID=<java pid> SIGNAL=KILL scripts/chaos_kill.sh <dataset-dir> <seconds-before-signal>
#
# Creates a fresh organization, uploads the dataset's ledger file, waits the given seconds (so the
# worker is mid-import), sends SIGNAL to the app (KILL: a crash, the lease must expire and another
# worker resume; TERM: a graceful stop, the job must be handed back at once without spending an
# attempt), and prints the job and batch state right after. Then restart the app however you started
# it and run with PHASE=verify ORG=<printed org> BATCH=<printed batch>: it waits for the import and
# checks the counts against expected.json and that no source row was stored twice.
#
# Environment as bench_import.sh (BASE, EMAIL, RECONFLOW_SEED_PASSWORD, PSQL).
set -euo pipefail

DATASET=${1:?usage: chaos_kill.sh <dataset-dir> <seconds>}
DELAY=${2:-3}
BASE=${BASE:-http://localhost:8080}
EMAIL=${EMAIL:-admin@acme.test}
PASSWORD=${RECONFLOW_SEED_PASSWORD:-reconflow-local-demo}
PSQL=${PSQL:-docker compose exec -T -e PGPASSWORD=${DB_PASSWORD:-} postgres psql -U ${DB_USER:-reconflow} -d paymentreconflow -qAt}
SIGNAL=${SIGNAL:-KILL}
PHASE=${PHASE:-kill}
expected="$DATASET/expected.json"

sql() { $PSQL -c "$1"; }
token() {
  curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r .accessToken
}
state() {
  sql "SELECT 'job: ' || j.status || ', attempts ' || j.attempts || ', lease ' || coalesce(j.lease_until::text, '-')
              || ' | batch: ' || b.status || ', checkpoint ' || b.last_committed_row || ', valid ' || b.valid_rows
         FROM import_batch b JOIN job j ON j.payload ->> 'batchId' = b.id::text
        WHERE b.id = '$BATCH' ORDER BY j.created_at DESC LIMIT 1"
}

if [ "$PHASE" = kill ]; then
  : "${APP_PID:?set APP_PID to the java process id of the app}"
  ORG=$(uuidgen | tr 'A-Z' 'a-z')
  sql "INSERT INTO organization (id, name) VALUES ('$ORG', 'chaos $ORG');
       INSERT INTO membership (user_id, organization_id, role)
       SELECT id, '$ORG', 'ORG_ADMIN' FROM app_user WHERE email = '$EMAIL';" >/dev/null
  TOKEN=$(token)
  BATCH=$(curl -sf -X POST "$BASE/api/v1/imports" -H "Authorization: Bearer $TOKEN" -H "X-Organization-Id: $ORG" \
    -F "file=@$DATASET/ledger.csv;type=text/csv" -F "sourceType=INTERNAL_LEDGER" | jq -r .id)
  sleep "$DELAY"
  echo "before SIG$SIGNAL: $(state)"
  kill -s "$SIGNAL" "$APP_PID"
  while kill -0 "$APP_PID" 2>/dev/null; do sleep 0.2; done
  echo "after SIG$SIGNAL:  $(state)"
  echo "ORG=$ORG BATCH=$BATCH"
  exit 0
fi

: "${ORG:?}" "${BATCH:?}"
TOKEN=$(token)
start=$(date +%s)
while true; do
  st=$(curl -sf "$BASE/api/v1/imports/$BATCH" -H "Authorization: Bearer $TOKEN" -H "X-Organization-Id: $ORG" | jq -r .status)
  case "$st" in COMPLETED|COMPLETED_WITH_ERRORS|FAILED) break ;; esac
  sleep 1
done
echo "finished after restart in $(( $(date +%s) - start )) s: $(state)"
got=$(sql "SELECT total_rows||'/'||valid_rows||'/'||error_rows FROM import_batch WHERE id = '$BATCH'")
want=$(jq -r '"\(.ledger.totalRows)/\(.ledger.validRows)/\(.ledger.errorRows)"' "$expected")
dups=$(sql "SELECT count(*) FROM (SELECT source_row_number FROM transaction_record WHERE organization_id = '$ORG'
              AND batch_id = '$BATCH' GROUP BY 1 HAVING count(*) > 1) d")
records=$(sql "SELECT count(*) FROM transaction_record WHERE organization_id = '$ORG'")
echo "counts $got (expected $want), records $records, source rows stored twice: $dups"
[ "$got" = "$want" ] && [ "$dups" = 0 ] && echo "PASS: identical to a clean import" || { echo FAIL; exit 1; }
