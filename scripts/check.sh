#!/usr/bin/env sh
set -eu

mode=${1:-full}
repo=$( CDPATH=; export CDPATH; cd -- "$(dirname -- "$0")/.." && pwd)

( cd "$repo" && docker compose config --quiet; )

if [ "$mode" = "quick" ]; then
  echo "Quick structural checks passed. This is NOT full runtime proof."
  exit 0
fi
if [ "$mode" != "full" ]; then
  echo "Usage: $0 [quick|full]" >&2
  exit 2
fi

project="scenery-foundry-check-$$-$(date +%s)"
cleanup_done=false
cleanup() {
  [ "$cleanup_done" = false ] || return 0
  cleanup_done=true
  ( cd "$repo" && docker compose --project-name "$project" down --rmi local --volumes --remove-orphans; ) ||
    echo "Warning: Compose cleanup did not complete successfully." >&2
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command in java node npm uv docker; do
  command -v "$command" >/dev/null 2>&1 || { echo "Required command '$command' is not available." >&2; exit 1; }
done

java_version=$(java -version 2>&1 | sed -n '1p')
printf '%s\n' "$java_version" | grep -Eq 'version "25([."]|$)' || {
  echo "Full verification requires JDK 25; found: $java_version" >&2
  exit 1
}
node_version=$(node --version)
node_semver=${node_version#v}
node_major=${node_semver%%.*}
node_minor_patch=${node_semver#*.}
node_minor=${node_minor_patch%%.*}
node_patch=${node_minor_patch#*.}
case "$node_version" in
  v*.*.*) ;;
  *) echo "Full verification requires Node.js 24.19.0 or later in major 24; found: $node_version" >&2; exit 1 ;;
esac
case "$node_major:$node_minor:$node_patch" in
  *::* | *[!0-9:]* | *:*:*:*) echo "Full verification requires Node.js 24.19.0 or later in major 24; found: $node_version" >&2; exit 1 ;;
esac
[ "$node_major" -eq 24 ] && [ "$node_minor" -ge 19 ] || {
  echo "Full verification requires Node.js 24.19.0 or later in major 24; found: $node_version" >&2
  exit 1
}
npm_version=$(npm --version)
printf '%s\n' "$npm_version" | grep -Eq '^11\.17\.' || {
  echo "Full verification requires npm 11.17; found: $npm_version" >&2
  exit 1
}
uv_version=$(uv --version)
printf '%s\n' "$uv_version" | grep -Eq '^uv 0\.12\.3([[:space:]]|$)' || {
  echo "Full verification requires uv 0.12.3; found: $uv_version" >&2
  exit 1
}
if ! python_output=$( cd "$repo/geometry-worker" && uv run --locked python --version 2>&1); then
  echo "Full verification requires the locked Python 3.14 runtime; uv command failed: $python_output" >&2
  exit 1
fi
python_version=$(printf '%s\n' "$python_output" | grep -E '^Python 3\.14\.' | sed -n '1p' || true)
printf '%s\n' "$python_version" | grep -Eq '^Python 3\.14\.' || {
  echo "Full verification requires the locked Python 3.14 runtime; output: $python_output" >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Full verification requires a reachable Docker daemon." >&2
  exit 1
}

rm -rf "$repo/backend/target" "$repo/frontend/dist" "$repo/frontend/.vite" \
  "$repo/geometry-worker/.pytest_cache" "$repo/geometry-worker/.ruff_cache"

( cd "$repo/backend" && ./mvnw clean test; )
report="$repo/backend/target/surefire-reports/TEST-com.product.PlatformMigrationIntegrationTest.xml"
[ -f "$report" ] || { echo "Missing PlatformMigrationIntegrationTest Surefire report." >&2; exit 1; }
grep -Eq '<testsuite[^>]*tests="[1-9][0-9]*"' "$report" &&
  grep -Eq '<testsuite[^>]*errors="0"' "$report" &&
  grep -Eq '<testsuite[^>]*failures="0"' "$report" &&
  grep -Eq '<testsuite[^>]*skipped="0"' "$report" || {
  echo "PlatformMigrationIntegrationTest requires executed tests with zero errors, failures, and skips." >&2
  exit 1
}
set -- "$repo/backend/target/surefire-reports"/TEST-*.xml
[ -f "$1" ] || { echo "Missing Surefire test reports." >&2; exit 1; }
for report in "$@"; do
  grep -Eq '<testsuite[^>]*tests="[1-9][0-9]*"' "$report" &&
    grep -Eq '<testsuite[^>]*errors="0"' "$report" &&
    grep -Eq '<testsuite[^>]*failures="0"' "$report" &&
    grep -Eq '<testsuite[^>]*skipped="0"' "$report" || {
    echo "Surefire report $report requires executed tests with zero errors, failures, and skips." >&2
    exit 1
  }
done
( cd "$repo/frontend" && npm ci && npm test && npm run build; )
( cd "$repo/geometry-worker" && uv sync --locked && uv run ruff check . && uv run ruff format --check . && uv run pytest -q; )

( cd "$repo" && docker compose --project-name "$project" build && docker compose --project-name "$project" up --wait; )
echo "Full verification passed: toolchains, tests, PostgreSQL boundary, images, and stack health."
