#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
RUN_ID=${1:-"smoke_$(date -u +%Y%m%d%H%M%S)"}
BASE_URL=${PERF_BASE_URL:-http://127.0.0.1:8080}
RESULT_DIR="$ROOT_DIR/results/$RUN_ID"
DATA_FILE="$ROOT_DIR/data/$RUN_ID/seckill-requests.csv"
JTL_FILE="$RESULT_DIR/smoke.jtl"

mkdir -p "$RESULT_DIR"
python3 "$SCRIPT_DIR/fixtures.py" --base "$BASE_URL" prepare --run-id "$RUN_ID" --users 1 --shops 1 --coupons 1 --stock 1

jmeter -n -t "$ROOT_DIR/jmeter/seckill-smoke.jmx" -l "$JTL_FILE" \
  -JbaseUrl="$BASE_URL" -JdataFile="$DATA_FILE" -Jthreads=1 -JrampUp=1 -Jduration=10 \
  '-Jsample_variables=api_success,business_success,business_message,order_no,username,coupon_id'

python3 "$SCRIPT_DIR/fixtures.py" --base "$BASE_URL" verify --run-id "$RUN_ID" --jtl "$JTL_FILE"
python3 "$SCRIPT_DIR/fixtures.py" --base "$BASE_URL" summarize --run-id "$RUN_ID" --jtl "$JTL_FILE"
printf 'Smoke completed. Clean up with: python3 %s/fixtures.py --base %s cleanup --run-id %s\n' "$SCRIPT_DIR" "$BASE_URL" "$RUN_ID"
