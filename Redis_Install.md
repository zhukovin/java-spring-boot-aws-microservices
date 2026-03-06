# Redis 6 Installation on EC2 (co-located with Kafka)

> All commands in Steps 1-4 run **on the EC2 instance** (via SSH).
> Commands in Step 5 run **on your local Mac**.

---

## Step 1 — Install Redis

```bash
sudo dnf install -y redis6

sudo systemctl enable redis6
sudo systemctl start redis6
```

**Verify:**
```bash
sudo systemctl status redis6
# Expected: active (running)
```

---

## Step 2 — Configure Redis to accept connections from the VPC

By default Redis binds to `127.0.0.1` only. ECS tasks connect via the private network interface, so Redis must listen on all interfaces.

Find the config file:
```bash
sudo find /etc -name "redis*.conf" 2>/dev/null
# Typically: /etc/redis6/redis6.conf
```

Update the bind address:
```bash
sudo sed -i 's/^bind 127.0.0.1/bind 0.0.0.0/' /etc/redis6/redis6.conf

# Verify the change
grep "^bind" /etc/redis6/redis6.conf
# Expected: bind 0.0.0.0
```

Restart Redis to apply:
```bash
sudo systemctl restart redis6

# Verify it's listening on all interfaces
ss -tlnp | grep 6379
# Expected: 0.0.0.0:6379
```

---

## Step 3 — Get the private IP

```bash
PRIVATE_IP=$(hostname -I | awk '{print $1}')
echo "REDIS_HOST=${PRIVATE_IP}"
```

This is the value you'll pass as `REDIS_HOST` to the ECS task definition in Step 4.

---

## Step 4 — Allow ECS tasks to reach Redis

> Run on your **local Mac**.

In the AWS Console → **EC2 → Security Groups** → find `orderflow-kafka-sg` → add inbound rule:

| Type | Port | Source |
|---|---|---|
| Custom TCP | 6379 | `orderflow-dev-tasks` SG ID |

---

## Step 5 — Pass REDIS_HOST to the api-gateway ECS task

> Run on your **local Mac**.

```bash
PRIVATE_IP=<private-ip-from-step-3>
CLUSTER="orderflow-dev"
TASK_FAMILY="orderflow-api-gateway"

TASK_DEF=$(aws ecs describe-task-definition \
  --task-definition "${TASK_FAMILY}" --query 'taskDefinition' --output json)

NEW_TASK_DEF=$(echo "${TASK_DEF}" | jq \
  --arg REDIS_HOST "${PRIVATE_IP}" \
  '.containerDefinitions[0].environment += [{"name":"REDIS_HOST","value":$REDIS_HOST}]
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

**Verify REDIS_HOST is in the task definition:**
```bash
aws ecs describe-task-definition \
  --task-definition "orderflow-api-gateway" \
  --query 'taskDefinition.containerDefinitions[0].environment' \
  --output table
# Expected: REDIS_HOST row showing the private IP
```

---

## Step 6 — Verify Redis health via the API gateway

> Run on your **local Mac**.

```bash
ALB_DNS=$(cd infra && terraform output -raw alb_dns_name)

curl -s "http://${ALB_DNS}/actuator/health" | jq '.components.redis'
# Expected: {"status":"UP","details":{"version":"6.x.x"}}
```
