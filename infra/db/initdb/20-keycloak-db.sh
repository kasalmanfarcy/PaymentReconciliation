#!/bin/sh
# Runs once, when a PostgreSQL volume is first initialised. Only the local rehearsal of the deployment
# (deploy/compose.local.yaml) sets KEYCLOAK_DB_PASSWORD and mounts the script: there Keycloak keeps
# its data in PostgreSQL, as it does deployed (M11.4). The development compose.yaml runs Keycloak in
# start-dev mode on its embedded database, so this does nothing there.
set -eu
if [ -z "${KEYCLOAK_DB_PASSWORD:-}" ] || [ ! -f /reconflow/bootstrap-keycloak.sql ]; then
  echo "20-keycloak-db.sh: no KEYCLOAK_DB_PASSWORD; Keycloak's database not created (not needed in development)"
  exit 0
fi
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v kc_pw="$KEYCLOAK_DB_PASSWORD" -f /reconflow/bootstrap-keycloak.sql
