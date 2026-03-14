#!/usr/bin/env bash
# teardown.sh — destroys all AWS resources created by up.sh.
# Safe to run multiple times (idempotent).
#
# Usage: ./teardown.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INFRA_DIR="${SCRIPT_DIR}/infra"
AWS_REGION="us-east-1"
SSH_KEY_PATH="${HOME}/.ssh/orderflow-kafka"
SERVICES=("order-service" "inventory-service" "payment-service" "api-gateway")

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

ok()      { echo -e "${GREEN}✓${NC} $*"; }
warn()    { echo -e "${YELLOW}⚠${NC} $*"; }
err()     { echo -e "${RED}✗${NC} $*"; }
section() { echo ""; echo -e "${YELLOW}=== $* ===${NC}"; echo ""; }

# ── Phase 0: Pre-flight checks and confirmation ───────────────────────────────
section "Pre-flight checks"

if ! command -v terraform &>/dev/null; then
  err "terraform not found on PATH"; exit 1
fi

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ok "AWS account: ${ACCOUNT_ID}"

BUCKET="orderflow-terraform-state-${ACCOUNT_ID}"

if ! aws s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
  warn "S3 state bucket not found — resources may already be torn down"
fi

if [[ ! -f "${INFRA_DIR}/terraform.tfvars" ]]; then
  warn "infra/terraform.tfvars not found — terraform destroy may fail"
fi

echo ""
echo "The following will be permanently destroyed:"
echo "  • ECS cluster, services, and tasks"
echo "  • Application Load Balancer"
echo "  • EC2 instance (Kafka + Redis)"
echo "  • ECR repositories and all images"
echo "  • DynamoDB tables (orders, inventory, outbox, idempotency)"
echo "  • Secrets Manager secrets"
echo "  • IAM roles, policies, and OIDC provider"
echo "  • CloudWatch log groups"
echo "  • S3 state bucket: ${BUCKET}"
echo "  • Local SSH key: ${SSH_KEY_PATH}"
echo "  • Local infra/terraform.tfvars"
echo ""
warn "This action is irreversible."
read -rp "Type 'yes' to confirm: " CONFIRM
[[ "${CONFIRM}" == "yes" ]] || { echo "Aborted."; exit 0; }

# ── Phase 1: Delete ECR images (must run before terraform destroy) ────────────
section "Phase 1: Deleting ECR images"

for SVC in "${SERVICES[@]}"; do
  REPO="orderflow/${SVC}"

  if ! aws ecr describe-repositories \
       --repository-names "${REPO}" \
       --region "${AWS_REGION}" &>/dev/null; then
    warn "ECR repo ${REPO} not found — skipping"
    continue
  fi

  IMAGE_IDS=$(aws ecr list-images \
    --repository-name "${REPO}" \
    --region "${AWS_REGION}" \
    --query 'imageIds[]' \
    --output json)

  if [[ "${IMAGE_IDS}" == "[]" ]]; then
    ok "ECR repo ${REPO} already empty"
    continue
  fi

  aws ecr batch-delete-image \
    --repository-name "${REPO}" \
    --region "${AWS_REGION}" \
    --image-ids "${IMAGE_IDS}" \
    --output json > /dev/null

  ok "Deleted images from: ${REPO}"
done

# ── Phase 2: Terraform destroy ────────────────────────────────────────────────
section "Phase 2: Terraform destroy"

cd "${INFRA_DIR}"
terraform init -input=false -reconfigure

if [[ -f "terraform.tfvars" ]]; then
  terraform destroy -var-file="terraform.tfvars" -auto-approve
else
  warn "No terraform.tfvars — attempting destroy without it"
  terraform destroy -auto-approve
fi

cd "${SCRIPT_DIR}"
ok "Terraform destroy complete"

# ── Phase 3: Delete S3 state bucket (must run after terraform destroy) ────────
section "Phase 3: Deleting S3 state bucket"

if ! aws s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
  ok "S3 bucket ${BUCKET} already gone"
else
  # Delete all object versions (versioning is enabled)
  VERSIONS=$(aws s3api list-object-versions \
    --bucket "${BUCKET}" \
    --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' \
    --output json 2>/dev/null)

  if [[ "${VERSIONS}" != "null" && "${VERSIONS}" != '{"Objects": null}' && "${VERSIONS}" != '{"Objects":[]}' ]]; then
    echo "${VERSIONS}" | aws s3api delete-objects \
      --bucket "${BUCKET}" \
      --delete "$(cat)" > /dev/null
    ok "Deleted object versions"
  fi

  # Delete all delete markers
  MARKERS=$(aws s3api list-object-versions \
    --bucket "${BUCKET}" \
    --query '{Objects: DeleteMarkers[].{Key:Key,VersionId:VersionId}}' \
    --output json 2>/dev/null)

  if [[ "${MARKERS}" != "null" && "${MARKERS}" != '{"Objects": null}' && "${MARKERS}" != '{"Objects":[]}' ]]; then
    echo "${MARKERS}" | aws s3api delete-objects \
      --bucket "${BUCKET}" \
      --delete "$(cat)" > /dev/null
    ok "Deleted delete markers"
  fi

  aws s3api delete-bucket \
    --bucket "${BUCKET}" \
    --region "${AWS_REGION}"
  ok "S3 bucket deleted: ${BUCKET}"
fi

# ── Phase 4: Clean up local files ─────────────────────────────────────────────
section "Phase 4: Cleaning up local files"

if [[ -f "${SSH_KEY_PATH}" ]]; then
  rm -f "${SSH_KEY_PATH}" "${SSH_KEY_PATH}.pub"
  ok "SSH key removed: ${SSH_KEY_PATH}"
else
  ok "SSH key already removed"
fi

if [[ -f "${INFRA_DIR}/terraform.tfvars" ]]; then
  rm -f "${INFRA_DIR}/terraform.tfvars"
  ok "Removed infra/terraform.tfvars"
else
  ok "terraform.tfvars already removed"
fi

# Restore backend.tf to its template form (undo the sed patch from bootstrap.sh)
if git -C "${SCRIPT_DIR}" rev-parse --git-dir &>/dev/null; then
  git -C "${SCRIPT_DIR}" checkout -- infra/backend.tf 2>/dev/null \
    && ok "Restored infra/backend.tf to template" \
    || warn "Could not restore backend.tf via git — restore manually if needed"
fi

rm -rf "${INFRA_DIR}/.terraform"
rm -f  "${INFRA_DIR}/tfplan"
rm -f  "${INFRA_DIR}/tf_outputs.json"
ok "Removed Terraform local cache"

# ── Phase 5: Verify ───────────────────────────────────────────────────────────
section "Phase 5: Verification"

CLUSTER=$(aws ecs describe-clusters \
  --clusters "orderflow-dev" \
  --region "${AWS_REGION}" \
  --query 'clusters[?status!=`INACTIVE`].clusterName' \
  --output text 2>/dev/null)
[[ -z "${CLUSTER}" ]] && ok "ECS cluster gone" || warn "ECS cluster still present: ${CLUSTER}"

EC2=$(aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=orderflow-kafka" \
            "Name=instance-state-name,Values=running,stopped,stopping,pending" \
  --query 'Reservations[].Instances[].InstanceId' \
  --output text --region "${AWS_REGION}" 2>/dev/null)
[[ -z "${EC2}" ]] && ok "EC2 instance gone" || warn "EC2 instance still present: ${EC2}"

ECR=$(aws ecr describe-repositories \
  --query 'repositories[?contains(repositoryName, `orderflow`)].repositoryName' \
  --region "${AWS_REGION}" --output text 2>/dev/null)
[[ -z "${ECR}" ]] && ok "ECR repos gone" || warn "ECR repos still present: ${ECR}"

aws s3api head-bucket --bucket "${BUCKET}" 2>/dev/null \
  && warn "S3 bucket still present: ${BUCKET}" \
  || ok "S3 bucket gone"

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}=== Teardown complete ===${NC}"
echo ""
echo "All OrderFlow AWS resources have been destroyed."
echo "To redeploy from scratch: ./up.sh"
echo ""
