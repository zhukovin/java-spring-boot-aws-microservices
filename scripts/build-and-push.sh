#!/usr/bin/env bash
# build-and-push.sh — builds all service JARs, Docker images, and pushes to ECR.
#
# Usage: ./scripts/build-and-push.sh <ecr-registry>
# Example: ./scripts/build-and-push.sh 123456789.dkr.ecr.us-east-1.amazonaws.com

set -euo pipefail

ECR_REGISTRY="${1:?Usage: build-and-push.sh <ecr-registry>}"
AWS_REGION="us-east-1"
IMAGE_TAG=$(git rev-parse --short HEAD)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

GREEN='\033[0;32m'
NC='\033[0m'
ok() { echo -e "${GREEN}✓${NC} $*"; }

echo ""
echo "=== Build & Push (tag: ${IMAGE_TAG}) ==="
echo ""

cd "${PROJECT_ROOT}"

# ── 1. Authenticate to ECR ────────────────────────────────────────────────────
echo "Authenticating to ECR..."
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${ECR_REGISTRY}"
ok "ECR login succeeded"

# ── 2. Build JARs ─────────────────────────────────────────────────────────────
echo "Building JARs..."
./mvnw package -DskipTests -q
ok "JARs built"

# ── 3. Verify all JARs exist ──────────────────────────────────────────────────
for SVC in order-service inventory-service payment-service api-gateway; do
  JAR=$(ls "${SVC}/target/"*.jar 2>/dev/null | grep -v sources | head -1)
  [[ -n "${JAR}" ]] || { echo "ERROR: JAR missing for ${SVC}" >&2; exit 1; }
  ok "JAR ready: ${JAR}"
done

# ── 4. Build and push Docker images ───────────────────────────────────────────
for SVC in order-service inventory-service payment-service api-gateway; do
  IMAGE_URI="${ECR_REGISTRY}/orderflow/${SVC}:${IMAGE_TAG}"
  LATEST_URI="${ECR_REGISTRY}/orderflow/${SVC}:latest"

  echo "Building ${SVC}..."
  docker build -t "${IMAGE_URI}" -f "${SVC}/Dockerfile" . -q

  docker tag "${IMAGE_URI}" "${LATEST_URI}"

  echo "Pushing ${SVC}..."
  docker push "${IMAGE_URI}" -q
  docker push "${LATEST_URI}" -q

  ok "Pushed ${SVC}:${IMAGE_TAG}"
done

echo ""
ok "All images pushed. Tag: ${IMAGE_TAG}"
echo ""
