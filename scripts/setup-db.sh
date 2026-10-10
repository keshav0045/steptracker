#!/bin/sh
set -eu

database_name="${DATABASE_NAME:-steptracker}"
database_user="${DATABASE_USER:-${PGUSER:-$(id -un)}}"

if ! command -v psql >/dev/null 2>&1 || ! command -v createdb >/dev/null 2>&1; then
  echo "Install PostgreSQL client tools (psql and createdb) and retry." >&2
  exit 1
fi

if psql -d postgres -v database_name="$database_name" -Atqc "SELECT 1 FROM pg_database WHERE datname = :'database_name'" | grep -q 1; then
  echo "Database '$database_name' already exists."
else
  createdb --maintenance-db=postgres --owner="$database_user" "$database_name"
  echo "Created database '$database_name' owned by '$database_user'."
fi

echo "The StepTracker API creates its tables when it starts."
