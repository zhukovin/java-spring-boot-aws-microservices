#!/usr/bin/env bash
# wait-for-kafka.sh — polls the EC2 instance via SSM until Kafka is ready.
# No SSH or open port 22 required; uses the SSM agent on the instance.
#
# Usage: ./scripts/wait-for-kafka.sh <instance-id>

set -euo pipefail

INSTANCE_ID="${1:?Usage: wait-for-kafka.sh <instance-id>}"
MAX_ATTEMPTS=40   # 40 × 30s = 20 minutes max
ATTEMPT=0

echo "Waiting for Kafka to be ready on ${INSTANCE_ID}..."

while [[ ${ATTEMPT} -lt ${MAX_ATTEMPTS} ]]; do
  ATTEMPT=$((ATTEMPT + 1))

  # Send a command to check the sentinel file
  CMD_ID=$(aws ssm send-command \
    --instance-ids "${INSTANCE_ID}" \
    --document-name "AWS-RunShellScript" \
    --parameters 'commands=["cat /var/lib/orderflow/kafka_ready 2>/dev/null || echo waiting"]' \
    --query 'Command.CommandId' \
    --output text 2>/dev/null) || { sleep 10; continue; }

  # Poll until the SSM command completes (up to 30s)
  for _ in $(seq 1 10); do
    sleep 3
    STATUS=$(aws ssm get-command-invocation \
      --command-id "${CMD_ID}" \
      --instance-id "${INSTANCE_ID}" \
      --query 'Status' \
      --output text 2>/dev/null) || STATUS="Pending"
    [[ "${STATUS}" == "Success" || "${STATUS}" == "Failed" ]] && break
  done

  OUTPUT=$(aws ssm get-command-invocation \
    --command-id "${CMD_ID}" \
    --instance-id "${INSTANCE_ID}" \
    --query 'StandardOutputContent' \
    --output text 2>/dev/null | tr -d '[:space:]') || OUTPUT="waiting"

  if [[ "${OUTPUT}" == "ready" ]]; then
    echo "Kafka is ready."
    exit 0
  fi

  echo "  Attempt ${ATTEMPT}/${MAX_ATTEMPTS} — still waiting (status: ${OUTPUT:-pending})..."
  sleep 20
done

echo "ERROR: Kafka did not become ready after $((MAX_ATTEMPTS * 30))s." >&2
echo "Check EC2 system logs: aws ec2 get-console-output --instance-id ${INSTANCE_ID}" >&2
echo "Or check cloud-init log: /var/log/orderflow-init.log" >&2
exit 1
