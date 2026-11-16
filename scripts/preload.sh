#!/usr/bin/env bash
# Background data for load tests (M9.6): imports and reconciles every file pair of a dataset made
# with `generate_dataset.py --preset preload` (several organizations, one pair per month), through
# the real API, then ANALYZEs. Query plans and latencies on an empty database mean nothing.
#
#   scripts/preload.sh target/datasets/preload
#
# Prints one line per organization: its id (for k6's ORG) and its last run's id (k6's RUN).
# Environment as bench_import.sh. Local only.
set -euo pipefail
ROOT=${1:?usage: preload.sh <dataset-root>}
EMAIL=${EMAIL:-admin@acme.test}
PSQL=${PSQL:-docker compose exec -T -e PGPASSWORD=${DB_PASSWORD:-} postgres psql -U ${DB_USER:-reconflow} -d paymentreconflow -qAt}
HERE=$(cd "$(dirname "$0")" && pwd)

BASE=${BASE:-http://localhost:8080}
PASSWORD=${RECONFLOW_SEED_PASSWORD:-reconflow-local-demo}
SKIP=${SKIP:-0} # organizations already loaded by an earlier, interrupted call

i=0
for orgdir in "$ROOT"/org-*; do
  i=$((i + 1))
  [ "$i" -le "$SKIP" ] && continue
  # One login per organization (tokens live 15 minutes; a login per import trips the rate limit).
  TOKEN=$(curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json'     -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r .accessToken)
  export TOKEN
  org=$(uuidgen | tr 'A-Z' 'a-z')
  $PSQL -c "INSERT INTO organization (id, name) VALUES ('$org', 'preload $(basename "$orgdir")');
            INSERT INTO membership (user_id, organization_id, role)
            SELECT id, '$org', 'ORG_ADMIN' FROM app_user WHERE email = '$EMAIL';" >/dev/null
  for month in "$orgdir"/*/; do
    ORG=$org "$HERE/bench_import.sh" "$month" 1 >/dev/null
  done
  run=$($PSQL -c "SELECT id FROM reconciliation_run WHERE organization_id = '$org' ORDER BY period_start DESC LIMIT 1")
  echo "$(basename "$orgdir") ORG=$org RUN=$run"
done
$PSQL -c "ANALYZE" >/dev/null
