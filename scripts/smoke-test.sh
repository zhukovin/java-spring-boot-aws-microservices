#!/usr/bin/env bash
# smoke-test.sh — end-to-end sanity check for the OrderFlow API
#
# Usage:
#   ./scripts/smoke-test.sh                      # local docker compose stack
#   BASE_URL=http://my-alb.amazonaws.com ./scripts/smoke-test.sh
#   API_KEY=my-prod-key BASE_URL=... ./scripts/smoke-test.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-dev-key-1}"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

pass() { echo -e "${GREEN}PASS${NC}  $1"; }
fail() { echo -e "${RED}FAIL${NC}  $1"; exit 1; }

assert_status() {
  local expected="$1" actual="$2" label="$3"
  [[ "$actual" == "$expected" ]] && pass "$label" || fail "$label (expected $expected, got $actual)"
}

echo ""
echo "=== OrderFlow smoke test against ${BASE_URL} ==="
echo ""

# ── Test 1: Happy path — create order ───────────────────────────────────────
IDEMPOTENCY_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/orders" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${API_KEY}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d '{
    "customerId": "customer-smoke-test",
    "items": [{"skuId":"SKU-001","productName":"Widget Pro","quantity":2,"unitPrice":29.99}]
  }')

HTTP_STATUS=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -1)
assert_status "201" "$HTTP_STATUS" "POST /orders → 201 Created"

ORDER_ID=$(echo "$BODY" | grep -o '"orderId":"[^"]*"' | cut -d'"' -f4)
[[ -n "$ORDER_ID" ]] && pass "Response contains orderId: ${ORDER_ID}" || fail "Response missing orderId"

STATUS=$(echo "$BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
[[ "$STATUS" == "PENDING" ]] && pass "Order status is PENDING" || fail "Expected PENDING, got $STATUS"

# ── Test 2: Idempotency — same key returns cached response ──────────────────
RESPONSE2=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/orders" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${API_KEY}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d '{
    "customerId": "customer-smoke-test",
    "items": [{"skuId":"SKU-001","productName":"Widget Pro","quantity":2,"unitPrice":29.99}]
  }')

HTTP_STATUS2=$(echo "$RESPONSE2" | tail -1)
BODY2=$(echo "$RESPONSE2" | head -1)
# Should return 2xx (201 or 200) with the same orderId
[[ "$HTTP_STATUS2" =~ ^2 ]] && pass "Duplicate idempotency key → 2xx (cached)" || fail "Duplicate key returned $HTTP_STATUS2"

ORDER_ID2=$(echo "$BODY2" | grep -o '"orderId":"[^"]*"' | cut -d'"' -f4)
[[ "$ORDER_ID2" == "$ORDER_ID" ]] && pass "Cached response has same orderId" || fail "Expected orderId $ORDER_ID, got $ORDER_ID2"

# ── Test 3: GET order ────────────────────────────────────────────────────────
GET_RESPONSE=$(curl -s -w "\n%{http_code}" "${BASE_URL}/orders/${ORDER_ID}" \
  -H "X-API-Key: ${API_KEY}")

GET_STATUS=$(echo "$GET_RESPONSE" | tail -1)
assert_status "200" "$GET_STATUS" "GET /orders/${ORDER_ID} → 200 OK"

# ── Test 4: Missing idempotency key → 400 ────────────────────────────────────
NO_KEY_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/orders" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${API_KEY}" \
  -d '{"customerId":"c1","items":[{"skuId":"SKU-001","productName":"W","quantity":1,"unitPrice":10}]}')

assert_status "400" "$NO_KEY_STATUS" "Missing Idempotency-Key → 400 Bad Request"

# ── Test 5: Missing API key → 401 ────────────────────────────────────────────
NO_AUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen 2>/dev/null || echo test-key)" \
  -d '{"customerId":"c1","items":[{"skuId":"SKU-001","productName":"W","quantity":1,"unitPrice":10}]}')

assert_status "401" "$NO_AUTH_STATUS" "Missing X-API-Key → 401 Unauthorized"

# ── Test 6: Invalid API key → 401 ────────────────────────────────────────────
BAD_AUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/orders" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: totally-wrong-key" \
  -H "Idempotency-Key: $(uuidgen 2>/dev/null || echo test-key)" \
  -d '{"customerId":"c1","items":[{"skuId":"SKU-001","productName":"W","quantity":1,"unitPrice":10}]}')

assert_status "401" "$BAD_AUTH_STATUS" "Invalid X-API-Key → 401 Unauthorized"

# ── Test 7: Non-existent order → 404 ─────────────────────────────────────────
NOT_FOUND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  "${BASE_URL}/orders/00000000-0000-0000-0000-000000000000" \
  -H "X-API-Key: ${API_KEY}")

assert_status "404" "$NOT_FOUND_STATUS" "Non-existent orderId → 404 Not Found"

echo ""
echo "=== All smoke tests passed ==="
echo ""
