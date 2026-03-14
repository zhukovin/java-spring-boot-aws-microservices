#!/usr/bin/env bash
# deploy.sh — post-terraform deployment orchestrator.
# Runs after "terraform apply" to build images, update secrets,
# deploy to ECS, seed data, and smoke-test.
#
# Usage: ./deploy.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INFRA_DIR="${SCRIPT_DIR}/infra"
AWS_REGION="us-east-1"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
ok()      { echo -e "${GREEN}✓${NC} $*"; }
section() { echo ""; echo -e "${YELLOW}=== $* ===${NC}"; echo ""; }

# ── Read Terraform outputs ────────────────────────────────────────────────────
section "Reading Terraform outputs"
cd "${INFRA_DIR}"

KAFKA_IP=$(terraform output -raw kafka_ec2_private_ip)
INSTANCE_ID=$(terraform output -raw kafka_ec2_instance_id)
CLUSTER=$(terraform output -raw ecs_cluster_name)
ALB_DNS=$(terraform output -raw alb_dns_name)

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

cd "${SCRIPT_DIR}"

ok "Kafka IP    : ${KAFKA_IP}"
ok "EC2 Instance: ${INSTANCE_ID}"
ok "ECS Cluster : ${CLUSTER}"
ok "ALB         : ${ALB_DNS}"
ok "ECR Registry: ${ECR_REGISTRY}"

# ── Phase 1: Wait for Kafka+Redis on EC2 ─────────────────────────────────────
section "Phase 1: Waiting for Kafka to be ready"
"${SCRIPT_DIR}/scripts/wait-for-kafka.sh" "${INSTANCE_ID}"

# ── Phase 2: Update Secrets Manager with real Kafka endpoint ──────────────────
section "Phase 2: Updating Kafka secret"
aws secretsmanager put-secret-value \
  --secret-id "orderflow/kafka" \
  --secret-string "{\"bootstrap_servers\":\"${KAFKA_IP}:9092\"}" \
  --region "${AWS_REGION}"
ok "Kafka secret updated: ${KAFKA_IP}:9092"

# ── Phase 2b: Set API keys if still placeholder ───────────────────────────────
section "Phase 2b: Checking API keys secret"
CURRENT_KEYS=$(aws secretsmanager get-secret-value \
  --secret-id "orderflow/api-keys" \
  --query 'SecretString' --output text --region "${AWS_REGION}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['api_keys'])")

if [[ "${CURRENT_KEYS}" == *"PLACEHOLDER"* ]]; then
  DEFAULT_KEY="orderflow-$(openssl rand -hex 8)"
  aws secretsmanager put-secret-value \
    --secret-id "orderflow/api-keys" \
    --secret-string "{\"api_keys\":\"${DEFAULT_KEY}\"}" \
    --region "${AWS_REGION}"
  ok "API keys secret initialised: ${DEFAULT_KEY}"
else
  ok "API keys secret already set — skipping"
fi

# ── Phase 3: Build and push Docker images ─────────────────────────────────────
section "Phase 3: Building and pushing Docker images"
"${SCRIPT_DIR}/scripts/build-and-push.sh" "${ECR_REGISTRY}"

# ── Phase 4: Deploy to ECS ────────────────────────────────────────────────────
section "Phase 4: Deploying to ECS"
"${SCRIPT_DIR}/scripts/update-task-defs.sh" "${CLUSTER}" "${ECR_REGISTRY}"

# ── Phase 5: Seed DynamoDB inventory ─────────────────────────────────────────
section "Phase 5: Seeding inventory"
"${SCRIPT_DIR}/scripts/seed-inventory.sh"

# ── Phase 6: Smoke test ───────────────────────────────────────────────────────
section "Phase 6: Smoke test"

# Fetch the first API key from Secrets Manager
API_KEY=$(aws secretsmanager get-secret-value \
  --secret-id "orderflow/api-keys" \
  --query 'SecretString' \
  --output text \
  --region "${AWS_REGION}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['api_keys'].split(',')[0])")

BASE_URL="http://${ALB_DNS}" API_KEY="${API_KEY}" \
  "${SCRIPT_DIR}/scripts/smoke-test.sh"

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}=== Deployment complete ===${NC}"
echo ""
echo "  API endpoint : http://${ALB_DNS}"
echo "  API key      : ${API_KEY}"
echo ""
