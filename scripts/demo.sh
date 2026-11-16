#!/usr/bin/env bash
# ReconFlow end to end (M11.9): upload a ledger and a gateway file, reconcile them, work an exception,
# read the report and the audit trail, and check every number against
# docs/samples/demo/expected.json. It is the demo and the smoke test of a deployment at once.
#
#   ./scripts/demo.sh                 # starts the stack (compose.yaml --profile full) if needed
#   ./scripts/demo.sh --no-start      # against a running stack, local or deployed:
#
#   BASE_URL=https://api.example.com KEYCLOAK_URL=https://auth.example.com DEMO_PASSWORD=... \
#     ./scripts/demo.sh --no-start
#
# Logs in like a native app (authorization code + PKCE, scripts/lib/oidc.sh), so it also works against
# a deployed realm, which has no password grant. Uses the demo organization Acme Payments and its
# uploader, investigator and admin accounts (the `demo` or `local` profile seeds them).
# Safe to run again: the files are then duplicates (409, the existing batches are reused) and a new
# run gives the same summary. Needs: curl, jq, openssl (and docker without --no-start).
# Optional: CURL_CA=<ca.pem> for a private CA (the local rehearsal of the deployment), DEMO_FAST=1 to
# skip the pauses between steps.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BASE_URL=${BASE_URL:-http://localhost:8080}
KEYCLOAK_URL=${KEYCLOAK_URL:-http://localhost:8180}
DEMO_PASSWORD=${DEMO_PASSWORD:-reconflow-local-demo}   # the local realm's documented demo password
DOMAIN=${DEMO_DOMAIN:-acme.test}
SAMPLES="$ROOT/docs/samples/demo"
EXPECTED="$SAMPLES/expected.json"

need() { command -v "$1" >/dev/null || { echo "Missing dependency: $1" >&2; exit 1; }; }
need curl; need jq; need openssl

CURL_OPTS=(--silent --show-error)
if [[ -n "${CURL_CA:-}" ]]; then CURL_OPTS+=(--cacert "$CURL_CA"); fi
# shellcheck source=lib/oidc.sh
source "$ROOT/scripts/lib/oidc.sh"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { printf '\n'; bold "▶ $*"; [[ -n "${DEMO_FAST:-}" ]] || sleep 1; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

wait_for() { # wait_for <what> <timeout-seconds> <command...>
  local what=$1 timeout=$2 i; shift 2
  for ((i = 0; i < timeout; i += 2)); do "$@" && return 0; sleep 2; done
  fail "timed out waiting for $what"
}

# api <token> <method> <path> [curl args...]: sets API_STATUS and API_BODY. Not called inside $(...),
# which would run it in a subshell and lose both.
api() {
  local token=$1 method=$2 path=$3 out; shift 3
  out=$(mktemp)
  API_STATUS=$(curl "${CURL_OPTS[@]}" -o "$out" -w '%{http_code}' -X "$method" \
    -H "Authorization: Bearer $token" "$@" "$BASE_URL/api/v1$path")
  API_BODY=$(cat "$out"); rm -f "$out"
}
expect() { [[ "$API_STATUS" == "$1" ]] || { echo "$API_BODY" >&2; fail "${2:-request} answered $API_STATUS, expected $1"; }; }

if [[ "${1:-}" != "--no-start" ]]; then
  step "Starting the stack (the first run builds the image: a few minutes)"
  need docker
  cd "$ROOT"
  [[ -f .env ]] || { cp .env.example .env; echo "  created .env from .env.example"; }
  docker compose --profile full up -d --build
  wait_for "Keycloak" 300 curl -fs -o /dev/null "$KEYCLOAK_URL/realms/reconflow/.well-known/openid-configuration"
  # Ready = the API answers 401 to an anonymous call (Traefik/compose route it only once it's up).
  wait_for "the API" 300 sh -c "curl -s -o /dev/null -w '%{http_code}' '$BASE_URL/api/v1/imports' | grep -q 401"
fi

step "Logging in (Keycloak, authorization code + PKCE) as uploader, investigator and admin @$DOMAIN"
UPLOADER=$(oidc_login "$KEYCLOAK_URL" "uploader@$DOMAIN" "$DEMO_PASSWORD")
INVESTIGATOR=$(oidc_login "$KEYCLOAK_URL" "investigator@$DOMAIN" "$DEMO_PASSWORD")
ADMIN=$(oidc_login "$KEYCLOAK_URL" "admin@$DOMAIN" "$DEMO_PASSWORD")
ok "three access tokens (15 minutes each)"

upload() { # upload <sourceType> <file>: sets BATCH; a re-upload reuses the existing batch
  api "$UPLOADER" POST /imports -F "file=@$2;type=text/csv" --form-string "sourceType=$1"
  case "$API_STATUS" in
    202) BATCH=$(jq -r .id <<<"$API_BODY"); echo "  batch $BATCH (202 Accepted: the worker imports it)" ;;
    409) BATCH=$(jq -r .existingBatchId <<<"$API_BODY"); echo "  batch $BATCH (409 duplicate-file: already imported, reused)" ;;
    *) echo "$API_BODY" >&2; fail "upload of $(basename "$2") answered $API_STATUS" ;;
  esac
}
import_done() {
  api "$UPLOADER" GET "/imports/$1"
  [[ "$(jq -r .status <<<"$API_BODY")" =~ ^(COMPLETED|COMPLETED_WITH_ERRORS|FAILED)$ ]]
}
check_import() { # check_import <id> <expected key>
  local got
  api "$UPLOADER" GET "/imports/$1"; expect 200 "GET import"
  got=$(jq -c '{status, totalRows, validRows, errorRows}' <<<"$API_BODY")
  echo "  $2: $got"
  jq -e --argjson got "$got" ".imports.$2 == \$got" "$EXPECTED" >/dev/null || fail "$2 import differs from expected.json"
  ok "$2 import as expected"
}

step "Uploading the internal ledger (19 rows)"
upload INTERNAL_LEDGER "$SAMPLES/ledger.csv"; LEDGER=$BATCH
step "Uploading the gateway settlement file (22 rows, 2 of them malformed)"
upload GATEWAY "$SAMPLES/gateway.csv"; GATEWAY=$BATCH

step "Waiting for the worker to import both"
wait_for "the ledger import" 120 import_done "$LEDGER"
wait_for "the gateway import" 120 import_done "$GATEWAY"
check_import "$LEDGER" ledger
check_import "$GATEWAY" gateway
api "$UPLOADER" GET "/imports/$GATEWAY/errors"; expect 200 "GET import errors"
jq -r '.content[] | "  row \(.rowNumber) \(.column // "-"): \(.code)"' <<<"$API_BODY"

step "Reconciling AED, 12-13 October 2026"
REQUEST=$(jq -c .run "$EXPECTED")
# A fresh Idempotency-Key per demo, so a second demo is a new run (the same key would return the
# first run: ADR 0006).
api "$INVESTIGATOR" POST /reconciliation-runs -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$(date +%s)-$RANDOM" -d "$REQUEST"
[[ "$API_STATUS" =~ ^20[02]$ ]] || { echo "$API_BODY" >&2; fail "run request answered $API_STATUS"; }
RUN=$(jq -r .id <<<"$API_BODY")
echo "  run $RUN ($API_STATUS)"
run_done() {
  api "$INVESTIGATOR" GET "/reconciliation-runs/$RUN"
  [[ "$(jq -r .status <<<"$API_BODY")" =~ ^(COMPLETED|FAILED)$ ]]
}
wait_for "the run" 180 run_done
[[ "$(jq -r .status <<<"$API_BODY")" == COMPLETED ]] || fail "the run failed: $(jq -r .failureReason <<<"$API_BODY")"
SUMMARY=$(jq -c .summary <<<"$API_BODY")
jq -r 'to_entries[] | "  \(.key): \(.value | to_entries | map("\(.key) \(.value)") | join(", "))"' <<<"$SUMMARY"
jq -e --argjson got "$SUMMARY" '.summary == $got' "$EXPECTED" >/dev/null || fail "the summary differs from expected.json"
ok "summary identical to expected.json"

step "Open exceptions of this run (the investigator's work queue)"
api "$INVESTIGATOR" GET "/exceptions?runId=$RUN&status=OPEN&size=50"; expect 200 "GET exceptions"
jq -r '.content | sort_by(.type, .reference)[] | "  \(.type)\t\(.reference)\t\(.currency) \(.amount)"' <<<"$API_BODY"
CASE=$(jq -r '.content[] | select(.reference == "DEMO-302") | .id' <<<"$API_BODY")
[[ -n "$CASE" ]] || fail "no open case for DEMO-302"

step "Resolving DEMO-302 (ledger 2500.00, gateway 2000.00) as GATEWAY_FEE, with If-Match"
ETAG=$(curl "${CURL_OPTS[@]}" -D - -o /dev/null -H "Authorization: Bearer $INVESTIGATOR" \
  "$BASE_URL/api/v1/exceptions/$CASE" | awk -F': ' 'tolower($1) == "etag" { print $2 }' | tr -d '\r')
echo "  GET /exceptions/$CASE -> ETag $ETAG"
api "$INVESTIGATOR" POST "/exceptions/$CASE/resolve" -H "If-Match: $ETAG" -H 'Content-Type: application/json' \
  -d '{"resolutionCode":"GATEWAY_FEE","note":"Gateway deducted its fee before settlement; confirmed on the statement."}'
expect 200 resolve
echo "  status $(jq -r .status <<<"$API_BODY"), resolution $(jq -r '.resolution.code // .resolution' <<<"$API_BODY")"
ok "resolved"

step "The same resolve again with the old ETag: a concurrent edit must not win silently"
api "$INVESTIGATOR" POST "/exceptions/$CASE/resolve" -H "If-Match: $ETAG" -H 'Content-Type: application/json' \
  -d '{"resolutionCode":"OTHER","note":"a second, stale attempt"}'
echo "  HTTP $API_STATUS $(jq -r .code <<<"$API_BODY")"
expect 409 "the stale resolve"
ok "stale version rejected (409)"

step "Reconciliation report for the run: which match, which don't, why, who resolved them"
api "$INVESTIGATOR" GET "/reports/reconciliation?runId=$RUN"; expect 200 report
jq -r '"  matched \(.matched.count) (\(.matched.amount)), mismatches \(.mismatches.count), missing in gateway \(.unmatchedLedger.count), missing in ledger \(.unmatchedGateway.count)",
       (.exceptionsByReason[] | "  \(.type): \(.count) (open \(.open), resolved \(.resolved))"),
       "  resolved by: \([.resolution.byResolver[] | "\(.displayName) \(.resolved)"] | join(", "))"' <<<"$API_BODY"

step "Audit trail of DEMO-302's case (org admin)"
api "$ADMIN" GET "/audit-events?entityType=EXCEPTION_CASE&entityId=$CASE"; expect 200 audit
jq -r '.content[] | "  \(.at)  \(.action)  actor \(.actorId // "system")"' <<<"$API_BODY"

printf '\n'; bold "Demo complete: every number matched docs/samples/demo/expected.json."
