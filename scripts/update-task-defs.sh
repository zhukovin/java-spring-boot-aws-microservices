#!/usr/bin/env bash
# update-task-defs.sh — updates each ECS task definition with the latest image tag
# and forces a rolling redeployment. Waits for all services to stabilise.
#
# Usage: ./scripts/update-task-defs.sh <cluster-name> <ecr-registry>

set -euo pipefail

CLUSTER="${1:?Usage: update-task-defs.sh <cluster-name> <ecr-registry>}"
ECR_REGISTRY="${2:?Usage: update-task-defs.sh <cluster-name> <ecr-registry>}"
AWS_REGION="us-east-1"
IMAGE_TAG=$(git rev-parse --short HEAD)

GREEN='\033[0;32m'
NC='\033[0m'
ok() { echo -e "${GREEN}✓${NC} $*"; }

echo ""
echo "=== Deploying to ECS cluster: ${CLUSTER} (tag: ${IMAGE_TAG}) ==="
echo ""

for SVC in order-service inventory-service payment-service api-gateway; do
  TASK_FAMILY="orderflow-${SVC}"
  IMAGE_URI="${ECR_REGISTRY}/orderflow/${SVC}:${IMAGE_TAG}"

  echo "Updating ${SVC}..."

  # Fetch current task definition, inject new image, strip read-only fields
  TASK_DEF=$(aws ecs describe-task-definition \
    --task-definition "${TASK_FAMILY}" \
    --query 'taskDefinition' \
    --output json \
    --region "${AWS_REGION}")

  NEW_TASK_DEF=$(echo "${TASK_DEF}" | jq \
    --arg IMAGE "${IMAGE_URI}" \
    '.containerDefinitions[0].image = $IMAGE
     | del(.taskDefinitionArn, .revision, .status,
           .requiresAttributes, .compatibilities,
           .registeredAt, .registeredBy)')

  NEW_TASK_ARN=$(aws ecs register-task-definition \
    --cli-input-json "${NEW_TASK_DEF}" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text \
    --region "${AWS_REGION}")

  aws ecs update-service \
    --cluster "${CLUSTER}" \
    --service "orderflow-${SVC}" \
    --task-definition "${NEW_TASK_ARN}" \
    --force-new-deployment \
    --region "${AWS_REGION}" \
    > /dev/null

  ok "Triggered deploy: ${SVC} → ${NEW_TASK_ARN##*/}"
done

echo ""
echo "Waiting for all services to stabilise..."

for SVC in order-service inventory-service payment-service api-gateway; do
  echo "  Waiting for orderflow-${SVC}..."
  aws ecs wait services-stable \
    --cluster "${CLUSTER}" \
    --services "orderflow-${SVC}" \
    --region "${AWS_REGION}"
  ok "orderflow-${SVC} is stable"
done

echo ""
ok "All services deployed and stable."
echo ""
