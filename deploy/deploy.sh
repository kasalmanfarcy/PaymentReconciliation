#!/usr/bin/env bash
# Deploy one image tag to this host (M11.4, M11.5). Run on the VM, from a checkout of the repository
# at the commit being deployed (the compose file, Traefik config and realm come from it):
#
#   deploy/deploy.sh sha-<40-hex commit>       # a deploy
#   deploy/deploy.sh sha-<previous commit>     # a rollback is the same command with the old tag
#
# Stages, each with its own log lines and exit code:
#   1. pull      the image for every role (skipped for a locally built image)
#   2. migrate   `prod,migrate` as reconflow_migrator, once. A failure stops here: nothing new has
#                started and the running version keeps serving. Rolling back to an older tag runs it
#                too, harmlessly: Flyway ignores migrations newer than the code it ships with.
#   3. edge      traefik and keycloak up (no-op when unchanged)
#   4. worker    recreated first: it has no traffic to drop, and it validates the schema on start
#   5. api       recreated; ready means /actuator/health/readiness is UP (database and storage)
#
# The api is recreated in place, so requests during its restart (about 10 s) get 502/503 from
# Traefik. docs/runbooks/deploy.md, "Downtime", says what removing that would take.
#
# Environment: RECONFLOW_ENV_FILE (default deploy/.env), RECONFLOW_LOCAL=1 for the local rehearsal
# (adds compose.local.yaml, uses deploy/.env.local and project name reconflow-rehearsal).
set -euo pipefail

TAG=${1:?usage: deploy.sh <image tag, e.g. sha-0123abc...>}
here=$(cd "$(dirname "$0")" && pwd)
files=(-f "$here/compose.prod.yaml")
project=reconflow-prod
env_file=${RECONFLOW_ENV_FILE:-$here/.env}
if [[ "${RECONFLOW_LOCAL:-}" == 1 ]]; then
  files+=(-f "$here/compose.local.yaml")
  project=reconflow-rehearsal
  env_file=${RECONFLOW_ENV_FILE:-$here/.env.local}
fi
[[ -f "$env_file" ]] || { echo "deploy: $env_file not found (copy .env.prod.example)" >&2; exit 1; }

export RECONFLOW_TAG=$TAG
compose() { docker compose -p "$project" --env-file "$env_file" "${files[@]}" "$@"; }
stage() { printf '\n== %s: %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }

wait_healthy() {   # wait_healthy <service> <timeout seconds>
  local id status
  for ((i = 0; i < $2; i += 2)); do
    id=$(compose ps -q "$1")
    status=$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo starting)
    [[ "$status" == healthy ]] && return 0
    sleep 2
  done
  echo "deploy: $1 not healthy after $2 s" >&2
  compose logs --tail 50 "$1" >&2
  return 1
}

wait_ready() {     # readiness (db + storage) on the api's management port, from inside its container
  for ((i = 0; i < $1; i += 2)); do
    compose exec -T api wget -q -O /dev/null http://127.0.0.1:8081/actuator/health/readiness 2>/dev/null && return 0
    sleep 2
  done
  echo "deploy: api not ready after $1 s" >&2
  compose logs --tail 50 api >&2
  return 1
}

stage "deploying $TAG ($project)"
if [[ "${RECONFLOW_LOCAL:-}" != 1 ]]; then
  stage "1/5 pull"
  compose --profile migrate pull migrate api worker
fi

stage "2/5 migrate (reconflow_migrator, prod,migrate)"
if [[ "${RECONFLOW_LOCAL:-}" == 1 ]]; then
  compose up -d --wait postgres minio
  compose up minio-init
fi
compose --profile migrate run --rm migrate
echo "migrate: exit 0"

stage "3/5 edge: traefik, keycloak"
compose up -d traefik keycloak
wait_healthy keycloak 300
wait_healthy traefik 60

stage "4/5 worker"
compose up -d --no-deps worker
wait_healthy worker 180

stage "5/5 api"
compose up -d --no-deps api
wait_healthy api 180
wait_ready 120

echo "$TAG" > "$here/.deployed-tag.$project"
stage "done: $TAG is serving"
compose ps
