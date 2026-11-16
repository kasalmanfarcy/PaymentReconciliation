#!/usr/bin/env bash
# Every migration from V13 on must say what to do if its release is rolled back (M11.5), in a
# header line starting "-- Rollback:". CONTRIBUTING.md, "Migrations", has the template.
#
#   scripts/check_migration_notes.sh        (CI runs it in the build job)
#
# V1-V12 predate the rule and are deliberately left alone: adding even a comment to an applied
# migration changes its checksum, and every deployed instance would then refuse to start.
set -euo pipefail

FIRST_VERSION_WITH_RULE=13
cd "$(dirname "$0")/.."

missing=()
for file in src/main/resources/db/migration/V*__*.sql; do
  version=$(basename "$file" | sed -E 's/^V([0-9]+)__.*/\1/')
  if (( version >= FIRST_VERSION_WITH_RULE )) && ! grep -q -- '-- Rollback:' "$file"; then
    missing+=("$file")
  fi
done

if (( ${#missing[@]} > 0 )); then
  echo "Migrations without a '-- Rollback:' note (CONTRIBUTING.md, Migrations):" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi
echo "Rollback notes: OK (rule applies from V${FIRST_VERSION_WITH_RULE})"
