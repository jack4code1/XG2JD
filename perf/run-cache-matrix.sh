#!/usr/bin/env bash
set -euo pipefail

RUN_ID="${1:-cache_$(date +%Y%m%d_%H%M%S)}"
BASE_URL="${PERF_BASE_URL:-http://127.0.0.1:8080}"
read -r -a THREADS <<< "${PERF_CACHE_THREADS:-10 50 100 200}"
read -r -a CASES <<< "${PERF_CACHE_CASES:-mysql redis caffeine}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${PYTHON:-python3}"
JMETER="${JMETER:-jmeter}"
JMETER_JVM_ARGS="${JMETER_JVM_ARGS:--Xms512m -Xmx512m -XX:+UseG1GC}"

if [[ -z "${PERF_MERCHANT_PASSWORD:-}" ]]; then
  echo "PERF_MERCHANT_PASSWORD is required" >&2
  exit 2
fi

# COSEC: validate run ID and fixed test-matrix selectors before using them in file paths or commands.
if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9_-]{1,40}$ ]]; then
  echo "run ID must contain only letters, digits, underscores, or hyphens" >&2
  exit 2
fi
for cache_case in "${CASES[@]}"; do
  [[ "$cache_case" =~ ^(mysql|redis|caffeine)$ ]] || { echo "invalid cache case" >&2; exit 2; }
done
for threads in "${THREADS[@]}"; do
  [[ "$threads" =~ ^(10|50|100|200)$ ]] || { echo "invalid thread count" >&2; exit 2; }
done

case "$BASE_URL" in
  http://127.0.0.1:*|http://localhost:*|http://[::1]:*) ;;
  *) echo "PERF_BASE_URL must be a localhost HTTP URL with an explicit port" >&2; exit 2 ;;
esac

host_and_port="${BASE_URL#http://}"
if [[ "$host_and_port" == \[* ]]; then
  JMETER_HOST="::1"
  JMETER_PORT="${host_and_port##*:}"
else
  JMETER_HOST="${host_and_port%:*}"
  JMETER_PORT="${host_and_port##*:}"
fi

run_jmeter() {
  local cache_case="$1"
  local threads="$2"
  local duration="$3"
  local suffix="$4"
  local strategy
  case "$cache_case" in
    mysql) strategy="MYSQL" ;;
    redis) strategy="REDIS" ;;
    caffeine) strategy="CAFFEINE" ;;
    *) echo "unexpected cache case: $cache_case" >&2; exit 2 ;;
  esac

  local jtl_path="$ROOT_DIR/target/perf-cache/$RUN_ID/${cache_case}-${threads}-${suffix}.jtl"
  # The JMeter CLI appends to an existing JTL. Each formal sample must start clean.
  rm -f "$jtl_path"
  JVM_ARGS="$JMETER_JVM_ARGS" "$JMETER" -n -t "$ROOT_DIR/perf/cache-test.jmx" \
    -l "$jtl_path" \
    -Jhost="$JMETER_HOST" -Jport="$JMETER_PORT" \
    -Jstrategy="$strategy" \
    -Jdata_file="$ROOT_DIR/target/perf-cache/$RUN_ID/requests.csv" \
    -Jthreads="$threads" -Jramp_seconds=5 -Jduration_seconds="$duration" \
    '-Jsample_variables=cache_success,cache_message,coupon_id'
}

cd "$ROOT_DIR"
# COSEC: cache_benchmark.py independently validates this local-only URL and run ID before file or HTTP use.
if [[ "${PERF_REUSE_RUN:-0}" == "1" ]]; then
  [[ -f "$ROOT_DIR/target/perf-cache/$RUN_ID/manifest.json" ]] || {
    echo "existing manifest not found for $RUN_ID" >&2
    exit 2
  }
else
  "$PYTHON" perf/cache_benchmark.py --base "$BASE_URL" prepare --run-id "$RUN_ID"
fi

for cache_case in "${CASES[@]}"; do
  for threads in "${THREADS[@]}"; do
    "$PYTHON" perf/cache_benchmark.py --base "$BASE_URL" setup-case --run-id "$RUN_ID" --case "$cache_case"
    if [[ "${PERF_SKIP_VALIDATION:-0}" != "1" ]]; then
      run_jmeter "$cache_case" "$threads" 20 validate
    fi
    "$PYTHON" perf/cache_benchmark.py --base "$BASE_URL" setup-case --run-id "$RUN_ID" --case "$cache_case"
    run_jmeter "$cache_case" "$threads" 65 formal
    "$PYTHON" perf/cache_benchmark.py --base "$BASE_URL" collect --run-id "$RUN_ID" \
      --case "$cache_case" --threads "$threads" \
      --jtl "$ROOT_DIR/target/perf-cache/$RUN_ID/${cache_case}-${threads}-formal.jtl"
  done
done

if [[ "${PERF_SKIP_REPORT:-0}" != "1" ]]; then
  "$PYTHON" perf/cache_benchmark.py report --run-id "$RUN_ID" --threads "${THREADS[@]}"
  echo "Results: $ROOT_DIR/target/perf-cache/$RUN_ID/report.md"
fi
echo "Cleanup: PERF_MERCHANT_PASSWORD=... $PYTHON perf/cache_benchmark.py --base $BASE_URL cleanup --run-id $RUN_ID"
