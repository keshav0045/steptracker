#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jdbc_jar=${POSTGRES_JDBC_JAR:-}

if [ -z "$jdbc_jar" ]; then
  jdbc_jar=$(find "${HOME:?}/.m2/repository/org/postgresql/postgresql" -type f \
    -name 'postgresql-[0-9]*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
    2>/dev/null | sort -V | tail -n 1 || true)
fi

if [ -z "$jdbc_jar" ] || [ ! -f "$jdbc_jar" ]; then
  echo "PostgreSQL JDBC driver not found. Run 'mvn package' or set POSTGRES_JDBC_JAR to its jar path." >&2
  exit 1
fi

mkdir -p "$project_root/target/classes"
if [ -d "$project_root/src/main/resources" ]; then
  cp -R "$project_root/src/main/resources/." "$project_root/target/classes/"
fi
javac --add-modules jdk.httpserver -cp "$jdbc_jar" -d "$project_root/target/classes" \
  "$project_root/src/main/java/StepTrackerRepository.java" \
  "$project_root/src/main/java/PasswordHasher.java" \
  "$project_root/src/main/java/StepTrackerService.java"
export STEPTRACKER_BIND_HOST="${STEPTRACKER_BIND_HOST:-127.0.0.1}"
if [ -z "${SESSION_COOKIE_SECURE+x}" ]; then
  case "$STEPTRACKER_BIND_HOST" in
    127.0.0.1|localhost|::1) SESSION_COOKIE_SECURE=false ;;
    *) SESSION_COOKIE_SECURE=true ;;
  esac
fi
export SESSION_COOKIE_SECURE
exec java --add-modules jdk.httpserver -cp "$project_root/target/classes:$jdbc_jar" StepTrackerService
