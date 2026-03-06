# AWS Cost Management — OrderFlow

## Check What's Running

### ECS Tasks (pay per vCPU/memory per second)
```bash
aws ecs list-clusters | jq -r '.clusterArns[]' | while read CLUSTER; do
  echo "=== $CLUSTER ==="
  aws ecs list-tasks --cluster "$CLUSTER" --desired-status RUNNING \
    --query 'length(taskArns)' --output text | xargs echo "  running tasks:"
done
```

### EC2 Instances (pay per hour)
```bash
aws ec2 describe-instances \
  --filters Name=instance-state-name,Values=running \
  --query 'Reservations[*].Instances[*].{ID:InstanceId,Type:InstanceType,Name:Tags[?Key==`Name`]|[0].Value,IP:PrivateIpAddress}' \
  --output table
```

### Load Balancers (pay per hour + LCU)
```bash
aws elbv2 describe-load-balancers \
  --query 'LoadBalancers[*].{Name:LoadBalancerName,DNS:DNSName,State:State.Code}' \
  --output table
```

### Secrets Manager (pay per secret per month)
```bash
aws secretsmanager list-secrets \
  --query 'SecretList[*].Name' --output table
```

### ECR (pay per GB stored)
```bash
aws ecr describe-repositories \
  --query 'repositories[*].repositoryName' --output table
```

### DynamoDB (pay per request — near zero at dev traffic)
```bash
aws dynamodb list-tables --output table
```

---

## Cost Breakdown

| Resource | Approx cost             | Stop how? |
|---|-------------------------|---|
| ECS Fargate tasks (4×) | ~$0.03/hr               | Scale desired count to 0 (see below) |
| ALB | ~$0.02/hr (~\$16/month) | Must delete — `terraform destroy` |
| EC2 t3.micro (Kafka + Redis) | ~$0.01/hr               | Stop instance (see below) |
| Secrets Manager (2 secrets) | ~$0.80/month            | Negligible |
| ECR storage | ~$0.01/month            | Negligible |
| DynamoDB | ~$0/month               | Pay-per-request at low traffic = near zero |

---

## Pause (cheapest — keeps config intact)

Scale ECS services to 0 and stop the EC2 instance. Re-deploying takes a few minutes.

```bash
# Scale all ECS services to 0
CLUSTER="orderflow-dev"
for SVC in order-service inventory-service payment-service api-gateway; do
  aws ecs update-service \
    --cluster "${CLUSTER}" \
    --service "orderflow-${SVC}" \
    --desired-count 0
  echo "Scaled down orderflow-${SVC}"
done

# Stop the EC2 instance (Kafka + Redis)
INSTANCE_ID=$(aws ec2 describe-instances \
  --filters Name=instance-state-name,Values=running \
            Name=tag:Name,Values=orderflow-kafka \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)
aws ec2 stop-instances --instance-ids "${INSTANCE_ID}"
echo "Stopped EC2: ${INSTANCE_ID}"
```

> The ALB costs ~$16/month even when all ECS tasks are at 0. If you're pausing for more than a few days, use **Full Teardown** below instead.

---

## Resume After Pause

```bash
# Start the EC2 instance
INSTANCE_ID=$(aws ec2 describe-instances \
  --filters Name=instance-state-name,Values=stopped \
            Name=tag:Name,Values=orderflow-kafka \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)
aws ec2 start-instances --instance-ids "${INSTANCE_ID}"

# Scale ECS services back to 1
CLUSTER="orderflow-dev"
for SVC in order-service inventory-service payment-service api-gateway; do
  aws ecs update-service \
    --cluster "${CLUSTER}" \
    --service "orderflow-${SVC}" \
    --desired-count 1
  echo "Scaled up orderflow-${SVC}"
done
```

> After starting the EC2 instance, the Kafka and Redis private IP may change.
> If so, update the `KAFKA_BOOTSTRAP_SERVERS` secret and `ORDER_SERVICE_URL` / `REDIS_HOST`
> ECS environment variables following the same steps used during initial deployment.

---

## Full Teardown (zero ongoing cost)

Destroys all Terraform-managed resources (ECS, ALB, ECR, IAM, Secrets Manager).
DynamoDB tables and the EC2 instance must be removed separately.

```bash
# Destroy Terraform infrastructure
cd infra
terraform destroy -var="github_org=<your-github-org>" -var="environment=dev"

# Terminate the EC2 instance
INSTANCE_ID=$(aws ec2 describe-instances \
  --filters Name=tag:Name,Values=orderflow-kafka \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)
aws ec2 terminate-instances --instance-ids "${INSTANCE_ID}"

# Delete DynamoDB tables
for TABLE in orders order_idempotency_keys order_outbox inventory inventory_idempotency; do
  aws dynamodb delete-table --table-name "${TABLE}" --region us-east-1
  echo "Deleted table: ${TABLE}"
done
```
