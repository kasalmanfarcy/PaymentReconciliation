#!/bin/sh
# Runs once, when compose's PostgreSQL volume is first initialised (docker-entrypoint-initdb.d), as
# the bootstrap superuser (POSTGRES_USER). Creates the migrator and application roles (M10.7).
# An existing volume is never re-initialised: run infra/db/bootstrap-roles.sql by hand instead
# (README, "Database roles").
set -eu
: "${DB_MIGRATOR_PASSWORD:?set DB_MIGRATOR_PASSWORD in .env}"
: "${DB_APP_PASSWORD:?set DB_APP_PASSWORD in .env}"
psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v migrator_pw="$DB_MIGRATOR_PASSWORD" -v app_pw="$DB_APP_PASSWORD" \
     -f /reconflow/bootstrap-roles.sql
