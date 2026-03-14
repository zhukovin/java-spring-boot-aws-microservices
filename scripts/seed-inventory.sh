#!/usr/bin/env bash
# seed-inventory.sh — seeds the DynamoDB inventory table with initial SKU data.
# Idempotent: skips items that already exist (uses condition expression).
#
# Usage: ./scripts/seed-inventory.sh

set -euo pipefail

AWS_REGION="us-east-1"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
ok()   { echo -e "${GREEN}✓${NC} $*"; }
skip() { echo -e "${YELLOW}~${NC} $*"; }

echo ""
echo "=== Seeding inventory table ==="
echo ""

seed_item() {
  local PK="$1" SKU="$2" NAME="$3" QTY="$4"

  aws dynamodb put-item \
    --table-name inventory \
    --item "{
      \"PK\":           {\"S\": \"${PK}\"},
      \"skuId\":        {\"S\": \"${SKU}\"},
      \"productName\":  {\"S\": \"${NAME}\"},
      \"availableQty\": {\"N\": \"${QTY}\"},
      \"reservedQty\":  {\"N\": \"0\"},
      \"version\":      {\"N\": \"0\"}
    }" \
    --condition-expression "attribute_not_exists(PK)" \
    --region "${AWS_REGION}" 2>/dev/null \
    && ok "Seeded ${SKU}: ${NAME} (qty: ${QTY})" \
    || skip "${SKU} already exists — skipped"
}

seed_item "ITEM#SKU-001" "SKU-001" "Widget Pro"    "100"
seed_item "ITEM#SKU-002" "SKU-002" "Gadget Plus"   "50"
seed_item "ITEM#SKU-003" "SKU-003" "Doohickey Max" "200"

echo ""
echo "Verifying inventory table..."
aws dynamodb scan \
  --table-name inventory \
  --query 'Items[*].{SKU:skuId.S,Name:productName.S,Available:availableQty.N}' \
  --output table \
  --region "${AWS_REGION}"
echo ""
