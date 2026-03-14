#!/usr/bin/env bash
# bootstrap.sh — one-time setup. Safe to re-run (fully idempotent).
# Creates the S3 state bucket, generates an SSH key pair, and writes terraform.tfvars.
#
# Usage: ./bootstrap.sh [github-org]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INFRA_DIR="${SCRIPT_DIR}/infra"
AWS_REGION="us-east-1"
SSH_KEY_PATH="${HOME}/.ssh/orderflow-kafka"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ok()   { echo -e "${GREEN}✓${NC} $*"; }
info() { echo -e "${YELLOW}→${NC} $*"; }

echo ""
echo "=== OrderFlow Bootstrap ==="
echo ""

# ── 1. Verify AWS credentials ─────────────────────────────────────────────────
info "Verifying AWS credentials..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ok "AWS account: ${ACCOUNT_ID}"

# ── 2. Prompt for GitHub org ──────────────────────────────────────────────────
GITHUB_ORG="${1:-}"
if [[ -z "${GITHUB_ORG}" ]]; then
  read -rp "GitHub username or org: " GITHUB_ORG
fi
ok "GitHub org: ${GITHUB_ORG}"

# ── 3. Create S3 state bucket (idempotent) ────────────────────────────────────
BUCKET="orderflow-terraform-state-${ACCOUNT_ID}"
info "Checking S3 state bucket: ${BUCKET}..."
if aws s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
  ok "S3 bucket already exists — skipping"
else
  info "Creating S3 bucket..."
  aws s3api create-bucket \
    --bucket "${BUCKET}" \
    --region "${AWS_REGION}"
  aws s3api put-bucket-versioning \
    --bucket "${BUCKET}" \
    --versioning-configuration Status=Enabled
  aws s3api put-bucket-encryption \
    --bucket "${BUCKET}" \
    --server-side-encryption-configuration \
      '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
  ok "S3 bucket created: ${BUCKET}"
fi

# ── 4. Patch backend.tf with real account ID ──────────────────────────────────
BACKEND_FILE="${INFRA_DIR}/backend.tf"
if grep -q "ACCOUNT_ID" "${BACKEND_FILE}"; then
  info "Patching backend.tf with account ID..."
  sed -i.bak "s/ACCOUNT_ID/${ACCOUNT_ID}/" "${BACKEND_FILE}"
  rm -f "${BACKEND_FILE}.bak"
  ok "backend.tf patched"
else
  ok "backend.tf already patched — skipping"
fi

# ── 5. Generate SSH key pair ──────────────────────────────────────────────────
if [[ -f "${SSH_KEY_PATH}" ]]; then
  ok "SSH key already exists: ${SSH_KEY_PATH}"
else
  info "Generating SSH key pair..."
  ssh-keygen -t ed25519 -f "${SSH_KEY_PATH}" -N "" -C "orderflow-kafka"
  chmod 600 "${SSH_KEY_PATH}"
  ok "SSH key generated: ${SSH_KEY_PATH}"
fi
EC2_PUBLIC_KEY=$(cat "${SSH_KEY_PATH}.pub")

# ── 6. Write terraform.tfvars ─────────────────────────────────────────────────
TFVARS_FILE="${INFRA_DIR}/terraform.tfvars"
info "Writing ${TFVARS_FILE}..."
cat > "${TFVARS_FILE}" <<EOF
github_org     = "${GITHUB_ORG}"
github_repo    = "java-spring-boot-aws-microservices"
environment    = "dev"
aws_region     = "${AWS_REGION}"
ec2_public_key = "${EC2_PUBLIC_KEY}"
EOF
ok "terraform.tfvars written"

# ── 7. Verify ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Bootstrap complete ==="
echo ""
echo "S3 bucket : ${BUCKET}"
echo "SSH key   : ${SSH_KEY_PATH}"
echo "tfvars    : ${TFVARS_FILE}"
echo ""
echo "Next steps:"
echo "  cd infra"
echo "  terraform init"
echo "  terraform apply -var-file=terraform.tfvars"
echo "  cd .. && ./deploy.sh"
echo ""
