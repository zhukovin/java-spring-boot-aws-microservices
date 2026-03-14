#!/usr/bin/env bash
# up.sh — single entry point. Runs bootstrap, terraform, and deploy in sequence.
#
# Usage: ./up.sh [github-org]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

YELLOW='\033[1;33m'
NC='\033[0m'
section() { echo ""; echo -e "${YELLOW}=== $* ===${NC}"; echo ""; }

section "Phase 0: Bootstrap"
"${SCRIPT_DIR}/bootstrap.sh" "${1:-}"

section "Phase 1: Terraform"
cd "${SCRIPT_DIR}/infra"
terraform init -input=false
terraform apply -var-file="terraform.tfvars" -auto-approve
cd "${SCRIPT_DIR}"

section "Phase 2: Deploy"
"${SCRIPT_DIR}/deploy.sh"
