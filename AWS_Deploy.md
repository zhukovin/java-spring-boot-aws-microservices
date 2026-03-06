# AWS Deployment Guide — OrderFlow Microservices

**Architecture summary:** 4 Spring Boot services (order-service, inventory-service, payment-service, api-gateway) running on ECS Fargate behind an ALB. Infrastructure is provisioned with Terraform. CI/CD uses GitHub Actions with OIDC.

**External dependencies you must provision separately:** Kafka (MSK or EC2), Redis (for api-gateway rate limiting — not in the Terraform), DynamoDB tables.

---

## Prerequisites

Verify each tool is installed before proceeding:

```bash
aws --version          # >= 2.x
terraform --version    # >= 1.5
docker version         # >= 24
java --version         # must be 21
./mvnw --version       # Maven wrapper
jq --version           # for JSON processing in deploy scripts
```

**Verify AWS credentials:**
```bash
aws sts get-caller-identity
```
Expected: JSON with your `Account`, `UserId`, `Arn`. **Do not proceed until this works.**

---

## Phase 1 — One-Time Terraform State Bootstrap

> These resources must exist before `terraform init` can run. Do this once per AWS account.

### Step 1.1 — Create S3 state bucket

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
AWS_REGION=us-east-1

aws s3api create-bucket \
  --bucket "orderflow-terraform-state-${ACCOUNT_ID}" \
  --region "${AWS_REGION}"

aws s3api put-bucket-versioning \
  --bucket "orderflow-terraform-state-${ACCOUNT_ID}" \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-encryption \
  --bucket "orderflow-terraform-state-${ACCOUNT_ID}" \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

**Verify:**
```bash
aws s3api get-bucket-versioning \
  --bucket "orderflow-terraform-state-${ACCOUNT_ID}" \
  --query 'Status' --output text
# Expected: Enabled
```

### Step 1.2 — Patch `backend.tf` with your account ID

Edit `infra/backend.tf` and replace `ACCOUNT_ID` with your actual 12-digit account ID:

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
sed -i.bak "s/ACCOUNT_ID/${ACCOUNT_ID}/" infra/backend.tf
cat infra/backend.tf  # visually confirm the bucket name looks correct
```

---

## Phase 2 — Provision Core Infrastructure with Terraform

### Step 2.1 — Initialize Terraform

```bash
cd infra
terraform init
```

**Verify:**
```
Terraform has been successfully initialized!
```
If you see a backend error, check the S3 bucket name in `backend.tf` matches what you created.

### Step 2.2 — Plan with required variables

```bash
GITHUB_ORG=<your-github-username-or-org>

terraform plan \
  -var="github_org=${GITHUB_ORG}" \
  -var="environment=dev" \
  -out=tfplan
```

**Verify:** Plan should show ~30-35 resources to create, zero to destroy. Review the list — particularly ECR repos, ECS cluster, IAM roles, ALB, Secrets Manager secrets.

### Step 2.3 — Apply

```bash
terraform apply tfplan
```

This takes ~3-5 minutes. **Verify:**
```bash
terraform output
```
Expected outputs:
- `ecr_repository_urls` — map of 4 ECR URLs
- `ecs_cluster_name` — `orderflow-dev`
- `alb_dns_name` — an AWS DNS name like `orderflow-dev-xxxx.us-east-1.elb.amazonaws.com`
- `github_actions_role_arn` — an IAM role ARN

Save these values — you'll need them in later steps:
```bash
terraform output -json > /tmp/tf_outputs.json
cat /tmp/tf_outputs.json
```

---

## Phase 3 — Provision DynamoDB Tables

> The Terraform IAM policies reference these tables but **Terraform does not create them**. Create them now.

### Step 3.1 — Create all required tables

```bash
AWS_REGION=us-east-1

# orders table — PK=PK (ORDER#<orderId>), SK=SK (METADATA)
# GSI_CustomerOrders: PK=customerId, SK=createdAt
aws dynamodb create-table \
  --table-name orders \
  --attribute-definitions \
    AttributeName=PK,AttributeType=S \
    AttributeName=SK,AttributeType=S \
    AttributeName=customerId,AttributeType=S \
    AttributeName=createdAt,AttributeType=S \
  --key-schema \
    AttributeName=PK,KeyType=HASH \
    AttributeName=SK,KeyType=RANGE \
  --global-secondary-indexes '[{
    "IndexName":"GSI_CustomerOrders",
    "KeySchema":[
      {"AttributeName":"customerId","KeyType":"HASH"},
      {"AttributeName":"createdAt","KeyType":"RANGE"}
    ],
    "Projection":{"ProjectionType":"ALL"}
  }]' \
  --billing-mode PAY_PER_REQUEST \
  --region "${AWS_REGION}"

# order idempotency keys table — PK=PK (IDEMPOTENCY#<key>)
aws dynamodb create-table \
  --table-name order_idempotency_keys \
  --attribute-definitions AttributeName=PK,AttributeType=S \
  --key-schema AttributeName=PK,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region "${AWS_REGION}"

# order outbox table — PK=PK (OUTBOX#<id>)
# GSI_PendingOutbox: PK=status, SK=createdAt
aws dynamodb create-table \
  --table-name order_outbox \
  --attribute-definitions \
    AttributeName=PK,AttributeType=S \
    AttributeName=status,AttributeType=S \
    AttributeName=createdAt,AttributeType=S \
  --key-schema AttributeName=PK,KeyType=HASH \
  --global-secondary-indexes '[{
    "IndexName":"GSI_PendingOutbox",
    "KeySchema":[
      {"AttributeName":"status","KeyType":"HASH"},
      {"AttributeName":"createdAt","KeyType":"RANGE"}
    ],
    "Projection":{"ProjectionType":"ALL"}
  }]' \
  --billing-mode PAY_PER_REQUEST \
  --region "${AWS_REGION}"

# inventory table — PK=PK (ITEM#<skuId>)
aws dynamodb create-table \
  --table-name inventory \
  --attribute-definitions AttributeName=PK,AttributeType=S \
  --key-schema AttributeName=PK,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region "${AWS_REGION}"

# inventory idempotency table — PK=PK (PROCESSED#<orderId>)
aws dynamodb create-table \
  --table-name inventory_idempotency \
  --attribute-definitions AttributeName=PK,AttributeType=S \
  --key-schema AttributeName=PK,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region "${AWS_REGION}"
```

**Verify all 5 tables are ACTIVE:**
```bash
for TABLE in orders order_idempotency_keys order_outbox inventory inventory_idempotency; do
  STATUS=$(aws dynamodb describe-table --table-name "$TABLE" \
    --query 'Table.TableStatus' --output text 2>/dev/null)
  echo "$TABLE: $STATUS"
done
# Expected: all show ACTIVE
```

### Step 3.2 — Seed inventory items

The inventory table must have stock entries for the SKUs you plan to order. Add at least one item:

```bash
aws dynamodb put-item \
  --table-name inventory \
  --item '{
    "PK":           {"S": "ITEM#SKU-001"},
    "skuId":        {"S": "SKU-001"},
    "productName":  {"S": "Widget"},
    "availableQty": {"N": "100"},
    "reservedQty":  {"N": "0"},
    "version":      {"N": "0"}
  }' \
  --region us-east-1
```

**Verify:**
```bash
aws dynamodb get-item \
  --table-name inventory \
  --key '{"PK":{"S":"ITEM#SKU-001"}}' \
  --region us-east-1 | jq .
# Expected: item with availableQty: 100
```

---

## Phase 4 — Provision Kafka

> The ECS task definition injects `KAFKA_BOOTSTRAP_SERVERS` from Secrets Manager. You need a real Kafka endpoint.

**Option A — Amazon MSK (recommended):** Provision an MSK cluster via the AWS console or a separate Terraform module. Note the bootstrap broker string (plaintext endpoint).

**Option B — EC2 Kafka:** Run Kafka on an EC2 instance in the same VPC. Note its private IP + port 9092. See `Kafka_install.md`.

### Step 4.1 — Update Kafka secret with real endpoint

Once you have a Kafka bootstrap string (e.g., `b-1.mycluster.xxx.kafka.us-east-1.amazonaws.com:9092`):

```bash
KAFKA_ENDPOINT="<your-kafka-bootstrap-servers>"

aws secretsmanager put-secret-value \
  --secret-id "orderflow/kafka" \
  --secret-string "{\"bootstrap_servers\":\"${KAFKA_ENDPOINT}\"}" \
  --region us-east-1
```

**Verify:**
```bash
aws secretsmanager get-secret-value \
  --secret-id "orderflow/kafka" \
  --query 'SecretString' --output text | jq .
# Expected: {"bootstrap_servers":"<your endpoint>"}
```

---

## Phase 5 — Update API Keys Secret

```bash
# Replace with your actual production API keys (comma-separated)
API_KEYS="prod-key-$(openssl rand -hex 16),prod-key-$(openssl rand -hex 16)"

aws secretsmanager put-secret-value \
  --secret-id "orderflow/api-keys" \
  --secret-string "{\"api_keys\":\"${API_KEYS}\"}" \
  --region us-east-1

echo "API keys set: ${API_KEYS}"  # save these — you'll need them to call the API
```

**Verify:**
```bash
aws secretsmanager get-secret-value \
  --secret-id "orderflow/api-keys" \
  --query 'SecretString' --output text | jq .
# Expected: {"api_keys":"prod-key-..."}
```

### Step 5.2 — Wire API_KEYS secret into the api-gateway ECS task

The ECS task definition does not inject `API_KEYS` automatically — this step links the secret to the container so the gateway uses your production keys instead of the hardcoded defaults.

```bash
CLUSTER="orderflow-dev"
TASK_FAMILY="orderflow-api-gateway"

API_KEYS_SECRET_ARN=$(aws secretsmanager describe-secret \
  --secret-id "orderflow/api-keys" \
  --query 'ARN' --output text)

TASK_DEF=$(aws ecs describe-task-definition \
  --task-definition "${TASK_FAMILY}" --query 'taskDefinition' --output json)

NEW_TASK_DEF=$(echo "${TASK_DEF}" | jq \
  --arg SECRET_ARN "${API_KEYS_SECRET_ARN}" \
  '.containerDefinitions[0].secrets += [{"name":"API_KEYS","valueFrom":($SECRET_ARN + ":api_keys::")}]
   | del(.taskDefinitionArn, .revision, .status,
         .requiresAttributes, .compatibilities,
         .registeredAt, .registeredBy)')

NEW_TASK_ARN=$(aws ecs register-task-definition \
  --cli-input-json "${NEW_TASK_DEF}" \
  --query 'taskDefinition.taskDefinitionArn' --output text)

aws ecs update-service \
  --cluster "${CLUSTER}" \
  --service "orderflow-api-gateway" \
  --task-definition "${NEW_TASK_ARN}" \
  --force-new-deployment

aws ecs wait services-stable --cluster "${CLUSTER}" --services "orderflow-api-gateway"
echo "Done"
```

**Verify:**
```bash
aws ecs describe-task-definition \
  --task-definition "orderflow-api-gateway" \
  --query 'taskDefinition.containerDefinitions[0].secrets' \
  --output table
# Expected: API_KEYS row present alongside KAFKA_BOOTSTRAP_SERVERS
```

---

## Phase 6 — Build & Push Docker Images

### Step 6.1 — Login to ECR

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
AWS_REGION=us-east-1
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${ECR_REGISTRY}"
```

**Verify:** `Login Succeeded`

### Step 6.2 — Build all service JARs

```bash
./mvnw package -DskipTests -q
```

**Verify:**
```bash
for SVC in order-service inventory-service payment-service api-gateway; do
  ls "${SVC}/target/"*.jar 2>/dev/null && echo "${SVC}: JAR found" || echo "${SVC}: MISSING JAR"
done
```

### Step 6.3 — Build and push Docker images

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com"
IMAGE_TAG=$(git rev-parse --short HEAD)

for SVC in order-service inventory-service payment-service api-gateway; do
  IMAGE_URI="${ECR_REGISTRY}/orderflow/${SVC}:${IMAGE_TAG}"
  echo "Building ${SVC}..."
  docker build -t "${IMAGE_URI}" -f "${SVC}/Dockerfile" . -q
  docker tag "${IMAGE_URI}" "${ECR_REGISTRY}/orderflow/${SVC}:latest"
  docker push "${IMAGE_URI}"
  docker push "${ECR_REGISTRY}/orderflow/${SVC}:latest"
  echo "Pushed ${SVC}: ${IMAGE_TAG}"
done
```

**Verify:**
```bash
for SVC in order-service inventory-service payment-service api-gateway; do
  COUNT=$(aws ecr describe-images \
    --repository-name "orderflow/${SVC}" \
    --query 'length(imageDetails)' --output text)
  echo "orderflow/${SVC}: ${COUNT} image(s)"
done
# Expected: each shows >= 1 image
```

---

## Phase 7 — Deploy to ECS

### Step 7.1 — Force ECS to pull the new images

```bash
CLUSTER="orderflow-dev"
IMAGE_TAG=$(git rev-parse --short HEAD)
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com"

for SVC in order-service inventory-service payment-service api-gateway; do
  TASK_FAMILY="orderflow-${SVC}"
  IMAGE_URI="${ECR_REGISTRY}/orderflow/${SVC}:${IMAGE_TAG}"

  # Fetch current task def, inject new image, register new revision
  TASK_DEF=$(aws ecs describe-task-definition \
    --task-definition "${TASK_FAMILY}" --query 'taskDefinition' --output json)

  NEW_TASK_DEF=$(echo "${TASK_DEF}" | jq \
    --arg IMAGE "${IMAGE_URI}" \
    '.containerDefinitions[0].image = $IMAGE
     | del(.taskDefinitionArn, .revision, .status,
           .requiresAttributes, .compatibilities,
           .registeredAt, .registeredBy)')

  NEW_TASK_ARN=$(aws ecs register-task-definition \
    --cli-input-json "${NEW_TASK_DEF}" \
    --query 'taskDefinition.taskDefinitionArn' --output text)

  aws ecs update-service \
    --cluster "${CLUSTER}" \
    --service "orderflow-${SVC}" \
    --task-definition "${NEW_TASK_ARN}" \
    --force-new-deployment > /dev/null

  echo "Triggered deploy for ${SVC} → ${NEW_TASK_ARN}"
done
```

### Step 7.2 — Wait for all services to stabilize

```bash
CLUSTER="orderflow-dev"

for SVC in order-service inventory-service payment-service api-gateway; do
  echo "Waiting for orderflow-${SVC}..."
  aws ecs wait services-stable \
    --cluster "${CLUSTER}" \
    --services "orderflow-${SVC}"
  echo "orderflow-${SVC}: STABLE"
done
```

> `aws ecs wait services-stable` polls every 15 seconds and times out after 40 checks (~10 minutes). If it times out, a task is crashing — check CloudWatch logs (see Troubleshooting).

**Verify all services running:**
```bash
CLUSTER="orderflow-dev"

aws ecs describe-services \
  --cluster "${CLUSTER}" \
  --services orderflow-order-service orderflow-inventory-service \
             orderflow-payment-service orderflow-api-gateway \
  --query 'services[*].{Name:serviceName,Running:runningCount,Desired:desiredCount,Status:status}' \
  --output table
# Expected: all show runningCount == desiredCount (1), status == ACTIVE
```

---

## Phase 8 — Smoke Test

### Step 8.1 — Get the ALB endpoint

```bash
cd infra
ALB_DNS=$(terraform output -raw alb_dns_name)
echo "API endpoint: http://${ALB_DNS}"
```

### Step 8.2 — Health check

```bash
curl -f "http://${ALB_DNS}/actuator/health"
# Expected: {"status":"UP"} (or similar Spring Boot health response)
```

### Step 8.3 — End-to-end order creation

```bash
API_KEY=$(aws secretsmanager get-secret-value \
  --secret-id "orderflow/api-keys" \
  --query 'SecretString' --output text | jq -r '.api_keys | split(",")[0]')

curl -s -X POST "http://${ALB_DNS}/orders" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${API_KEY}" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "customerId": "cust-123",
    "items": [
      {
        "skuId": "SKU-001",
        "productName": "Widget",
        "quantity": 2,
        "unitPrice": 9.99
      }
    ]
  }' | jq .
# Expected: JSON response with an orderId and status PENDING
```

**Verify the order was persisted:**
```bash
ORDER_ID="<orderId from above response>"

aws dynamodb get-item \
  --table-name orders \
  --key "{\"orderId\":{\"S\":\"${ORDER_ID}\"}}" \
  --region us-east-1 | jq .
# Expected: the full order item
```

**Check CloudWatch logs if anything fails:**
```bash
# View last 10 minutes of logs for any service
SVC=api-gateway  # or order-service, inventory-service, payment-service
aws logs tail "/ecs/orderflow/${SVC}" --follow --since 10m
```

---

## Phase 9 — Configure GitHub Actions for Continuous Deployment

Once the infrastructure is up, set these in your GitHub repo (**Settings → Secrets and variables → Actions → Variables**):

| Variable | Value |
|---|---|
| `AWS_ACCOUNT_ID` | your 12-digit account ID |
| `ENVIRONMENT` | `dev` |

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
GITHUB_ACTIONS_ROLE=$(cd infra && terraform output -raw github_actions_role_arn)

echo "Set GitHub variable AWS_ACCOUNT_ID = ${ACCOUNT_ID}"
echo "Set GitHub variable ENVIRONMENT = dev"
echo ""
echo "The OIDC role is already created: ${GITHUB_ACTIONS_ROLE}"
echo "No AWS credentials need to be stored in GitHub — OIDC handles it."
```

**Verify the CD pipeline works:**
- Push any commit to `main`
- Watch the **Actions** tab — the `CD` workflow should build all 4 images and deploy them
- All deploy jobs should show green ✓

---

## Troubleshooting Reference

| Symptom | Where to look |
|---|---|
| ECS task keeps restarting | `aws logs tail /ecs/orderflow/<service> --since 5m` |
| Task fails to start (no logs) | Check ECS service events: `aws ecs describe-services --cluster orderflow-dev --services orderflow-<svc> --query 'services[0].events[0:5]'` |
| Kafka connection refused | Verify `orderflow/kafka` secret value; check security group allows port 9092 from ECS tasks SG |
| ALB returns 503 | Health check failing — service not passing `/actuator/health`; check container health in ECS console |
| `terraform apply` fails on backend | S3 bucket doesn't exist or `ACCOUNT_ID` in `backend.tf` is still the placeholder |
| GitHub Actions OIDC error | Confirm `AWS_ACCOUNT_ID` variable is set in GitHub; confirm `github_org` matches your exact GitHub username/org |
