#!/usr/bin/env bash
# Restore drill (M9.17): back up the local database, restore it into a FRESH PostgreSQL instance,
# start the app against the copy and check it, timing every step. The measured total is the RTO
# this setup achieves for a database of this size (target ≤ 1 h, docs/scalability.md §6).
#
#   scripts/restore_drill.sh <org-id> <run-id>
#
# <org-id>/<run-id>: a known organization and one of its completed runs, whose report must come back
# identical from the restored copy. Everything is local: the compose PostgreSQL (source), a throwaway
# postgres:16.4-alpine container (target, removed at the end), the packaged jar (./mvnw package).
#
# Environment: SOURCE_PSQL / SOURCE_DUMP (how to reach the source; defaults: the compose postgres),
# DB_USER, DB_PASSWORD (the source's superuser, for pg_dump), RECONFLOW_SEED_PASSWORD, EMAIL, SOURCE_BASE (the running source
# app: its report is compared byte for byte), S3_* (the app's storage, for its readiness check).
# Writes target/drill/.
set -euo pipefail

ORG=${1:?usage: restore_drill.sh <org-id> <run-id>}
RUN=${2:?usage: restore_drill.sh <org-id> <run-id>}
DB_USER=${DB_USER:-reconflow}
SOURCE_PSQL=${SOURCE_PSQL:-docker compose exec -T -e PGPASSWORD=${DB_PASSWORD:-} postgres psql -U $DB_USER -d paymentreconflow -qAt}
SOURCE_DUMP=${SOURCE_DUMP:-docker compose exec -T -e PGPASSWORD=${DB_PASSWORD:-} postgres pg_dump -U $DB_USER -d paymentreconflow -Fc}
EMAIL=${EMAIL:-admin@acme.test}
PASSWORD=${RECONFLOW_SEED_PASSWORD:-reconflow-local-demo}
TARGET=reconflow-restore-drill
TARGET_PORT=55433
TARGET_PASSWORD=drill-local-only
OUT=target/drill
mkdir -p "$OUT"

now() { python3 -c 'import time; print(f"{time.time():.1f}")'; }
since() { python3 -c "print(f'{$(now) - $1:.1f}')"; }
target_psql() { docker exec -i -e PGPASSWORD=$TARGET_PASSWORD $TARGET psql -U postgres -d paymentreconflow -qAt "$@"; }
cleanup() { docker rm -f $TARGET >/dev/null 2>&1 || true; [ -n "${APP:-}" ] && kill "$APP" 2>/dev/null || true; }
trap cleanup EXIT

CHECKS="SELECT 'transaction_record', count(*) FROM transaction_record
  UNION ALL SELECT 'import_batch', count(*) FROM import_batch
  UNION ALL SELECT 'reconciliation_run', count(*) FROM reconciliation_run
  UNION ALL SELECT 'reconciliation_match', count(*) FROM reconciliation_match
  UNION ALL SELECT 'exception_case', count(*) FROM exception_case
  UNION ALL SELECT 'audit_event', count(*) FROM audit_event
  UNION ALL SELECT 'job', count(*) FROM job
  UNION ALL SELECT 'run summary', hashtext(row(matched_count, matched_amount, mismatch_count, mismatch_net_difference,
         unmatched_left_count, unmatched_left_amount, unmatched_right_count, unmatched_right_amount,
         duplicate_count)::text) FROM reconciliation_run WHERE id = '$RUN'"

start=$(now)
echo "1. backup (pg_dump -Fc)"
t=$(now); $SOURCE_DUMP > "$OUT/backup.dump"; dump_s=$(since "$t")
size=$(du -h "$OUT/backup.dump" | cut -f1)
$SOURCE_PSQL -c "$CHECKS" | sort > "$OUT/source-checks.txt"
echo "   $size in ${dump_s}s"

echo "2. fresh PostgreSQL instance"
t=$(now)
docker run -d --name $TARGET -e POSTGRES_PASSWORD=$TARGET_PASSWORD -e POSTGRES_DB=paymentreconflow \
  -p $TARGET_PORT:5432 postgres:16.4-alpine >/dev/null
until docker exec $TARGET pg_isready -U postgres -d paymentreconflow >/dev/null 2>&1; do sleep 0.5; done
sleep 2 # the image restarts the server once after initdb
until docker exec $TARGET pg_isready -U postgres -d paymentreconflow >/dev/null 2>&1; do sleep 0.5; done
instance_s=$(since "$t"); echo "   ready in ${instance_s}s"

echo "3. roles, then restore (pg_dump carries grants but no roles: M10.7's roles come from bootstrap-roles.sql)"
t=$(now)
# Roles first, or the dump's GRANTs to reconflow_app fail and the app can read nothing (or, restored as
# a superuser-owned schema, the app would be pointed at an owner and RLS bypassed). Fresh passwords:
# the drill's copy never shares credentials with the source.
docker cp infra/db/bootstrap-roles.sql $TARGET:/tmp/bootstrap-roles.sql
docker exec -e PGPASSWORD=$TARGET_PASSWORD $TARGET psql -U postgres -d paymentreconflow -q \
  -v migrator_pw=$TARGET_PASSWORD -v app_pw=$TARGET_PASSWORD -f /tmp/bootstrap-roles.sql
docker cp "$OUT/backup.dump" $TARGET:/tmp/backup.dump # parallel restore (--jobs) needs a file, not stdin
# --no-owner --role: every object is owned by the migrator, whoever owned it at the source.
docker exec -e PGPASSWORD=$TARGET_PASSWORD $TARGET \
  pg_restore -U postgres -d paymentreconflow --no-owner --role=reconflow_migrator --jobs 4 /tmp/backup.dump \
  2> "$OUT/restore-warnings.txt" || true
target_psql -c "ANALYZE" >/dev/null
restore_s=$(since "$t"); echo "   restored and analyzed in ${restore_s}s"

echo "4. data checks (row counts, a run's totals)"
target_psql -c "$CHECKS" | sort > "$OUT/target-checks.txt"
if diff -u "$OUT/source-checks.txt" "$OUT/target-checks.txt"; then echo "   identical"; else echo "   DIFFERENT"; exit 1; fi

echo "5. the app against the copy (Flyway validates every applied migration, Hibernate the schema)"
t=$(now)
DB_URL=jdbc:postgresql://localhost:$TARGET_PORT/paymentreconflow \
  DB_APP_PASSWORD=$TARGET_PASSWORD DB_MIGRATOR_PASSWORD=$TARGET_PASSWORD \
  java -Xmx512m -jar target/paymentreconflow-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,api \
  --server.port=8090 --management.server.port=8091 --reconflow.seed.enabled=false \
  --logging.level.com.reconflow=INFO --logging.level.org.hibernate.SQL=INFO > "$OUT/app.log" 2>&1 &
APP=$!
until curl -sf localhost:8091/actuator/health/readiness >/dev/null; do
  kill -0 $APP 2>/dev/null || { echo "   app failed to start; see $OUT/app.log"; exit 1; }
  sleep 0.5
done
app_s=$(since "$t"); echo "   ready in ${app_s}s"
token=$(curl -sf -X POST localhost:8090/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r .accessToken)
report=$(curl -sf "localhost:8090/api/v1/reports/reconciliation?runId=$RUN" -H "Authorization: Bearer $token" \
  -H "X-Organization-Id: $ORG" | jq -S -c 'del(.generatedAt)')
echo "$report" > "$OUT/report-from-restore.json"
if [ -n "${SOURCE_BASE:-}" ]; then # the running source app, e.g. http://localhost:8080
  stoken=$(curl -sf -X POST "$SOURCE_BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r .accessToken)
  curl -sf "$SOURCE_BASE/api/v1/reports/reconciliation?runId=$RUN" -H "Authorization: Bearer $stoken" \
    -H "X-Organization-Id: $ORG" | jq -S -c 'del(.generatedAt)' > "$OUT/report-from-source.json"
  cmp -s "$OUT/report-from-source.json" "$OUT/report-from-restore.json" \
    && echo "   report identical to the source's" || { echo "   report DIFFERS from the source's"; exit 1; }
fi
echo "   report from the restored copy: $(echo "$report" | jq -c '.totals // .summary // . | tostring | .[0:160]')"

total_s=$(since "$start")
echo
echo "backup ${dump_s}s, instance ${instance_s}s, restore ${restore_s}s, app ready ${app_s}s; total ${total_s}s (backup $size)"
