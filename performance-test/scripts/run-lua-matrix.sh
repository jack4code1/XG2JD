#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MATRIX_ID="${1:-lua_$(date -u +%Y%m%d_%H%M%S)}"
BASE_URL="${PERF_BASE_URL:-http://127.0.0.1:8080}"
read -r -a THREADS <<< "${PERF_SECKILL_THREADS:-50 100 200 500}"
USER_COUNT="${PERF_SECKILL_USERS:-5000}"
STOCK_PER_COUPON="${PERF_SECKILL_STOCK:-10000}"
RAMP_UP="${PERF_SECKILL_RAMP_UP:-5}"
DURATION="${PERF_SECKILL_DURATION:-120}"
JMETER="${JMETER:-jmeter}"
JMETER_JVM_ARGS="${JMETER_JVM_ARGS:--Xms512m -Xmx512m -XX:+UseG1GC}"

if [[ -z "${PERF_MERCHANT_PASSWORD:-}" ]]; then
  echo "PERF_MERCHANT_PASSWORD is required" >&2
  exit 2
fi

# COSEC: only validated local run identifiers and fixed numeric limits reach paths or subprocess arguments.
if [[ ! "$MATRIX_ID" =~ ^[A-Za-z0-9_-]{1,30}$ ]]; then
  echo "matrix ID must contain only letters, digits, underscores, or hyphens (max 30)" >&2
  exit 2
fi
case "$BASE_URL" in
  http://127.0.0.1:*|http://localhost:*|http://[::1]:*) ;;
  *) echo "PERF_BASE_URL must be a localhost HTTP URL with an explicit port" >&2; exit 2 ;;
esac
[[ "$USER_COUNT" =~ ^[1-9][0-9]{0,3}$ ]] && (( USER_COUNT <= 5000 )) || {
  echo "PERF_SECKILL_USERS must be 1-5000" >&2; exit 2;
}
[[ "$STOCK_PER_COUPON" =~ ^[1-9][0-9]{0,5}$ ]] && (( STOCK_PER_COUPON <= 100000 && STOCK_PER_COUPON > USER_COUNT )) || {
  echo "PERF_SECKILL_STOCK must exceed users and be <=100000" >&2; exit 2;
}
[[ "$RAMP_UP" =~ ^[1-9][0-9]{0,2}$ ]] || { echo "invalid ramp-up" >&2; exit 2; }
[[ "$DURATION" =~ ^[1-9][0-9]{0,3}$ ]] || { echo "invalid duration" >&2; exit 2; }
for threads in "${THREADS[@]}"; do
  [[ "$threads" =~ ^(50|100|200|500|1000)$ ]] || { echo "unsupported thread count: $threads" >&2; exit 2; }
done

run_case() {
  local suffix="$1"
  local threads="$2"
  local users="$3"
  local stock="$4"
  local run_id="${MATRIX_ID}_${suffix}"
  local result_dir="$ROOT_DIR/results/$run_id"
  local data_file="$ROOT_DIR/data/$run_id/seckill-requests.csv"
  local jtl_file="$result_dir/acceptance.jtl"

  mkdir -p "$result_dir"
  python3 "$ROOT_DIR/scripts/fixtures.py" --base "$BASE_URL" prepare --run-id "$run_id" \
    --users "$users" --shops 1 --coupons 1 --stock "$stock"

  # JMeter appends to an existing JTL, so a formal batch always starts with an empty result file.
  rm -f "$jtl_file"
  JVM_ARGS="$JMETER_JVM_ARGS" "$JMETER" -n -t "$ROOT_DIR/jmeter/seckill-smoke.jmx" -l "$jtl_file" \
    -JbaseUrl="$BASE_URL" -JdataFile="$data_file" -Jthreads="$threads" \
    -JrampUp="$RAMP_UP" -Jduration="$DURATION" \
    '-Jsample_variables=api_success,business_success,business_message,order_no,username,coupon_id'

  python3 "$ROOT_DIR/scripts/fixtures.py" --base "$BASE_URL" summarize --run-id "$run_id" --jtl "$jtl_file"
  python3 "$ROOT_DIR/scripts/fixtures.py" --base "$BASE_URL" verify --run-id "$run_id" \
    --jtl "$jtl_file" --timeout-seconds 180
  python3 "$ROOT_DIR/scripts/fixtures.py" --base "$BASE_URL" cleanup --run-id "$run_id"
}

cd "$ROOT_DIR"
# Warm Redis connections, Lua script loading, RabbitMQ publisher confirm, and the JVM before formal samples.
run_case warm 10 200 400
for threads in "${THREADS[@]}"; do
  run_case "t${threads}" "$threads" "$USER_COUNT" "$STOCK_PER_COUPON"
done

REPORT="$ROOT_DIR/results/${MATRIX_ID}-report.md"
{
  echo "| 并发 | QPS | P50 (ms) | P95 (ms) | P99 (ms) | 系统错误率 | 成功受理数 |"
  echo "| --: | --: | --: | --: | --: | --: | --: |"
  for threads in "${THREADS[@]}"; do
    summary="$ROOT_DIR/results/${MATRIX_ID}_t${threads}/summary.json"
    jq -r --arg threads "$threads" '"| " + $threads + " | " + (.throughput_qps | tostring) + " | "
      + (.p50_ms | tostring) + " | " + (.p95_ms | tostring) + " | " + (.p99_ms | tostring)
      + " | " + ((.error_rate * 100 | tostring) + "%") + " | " + (.business_success_requests | tostring) + " |"' "$summary"
  done
} > "$REPORT"
echo "Results: $REPORT"
