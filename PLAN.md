# OrderFlow Microservices - Implementation Plan

## Context

Building a production-quality interview demo project ("OrderFlow") that demonstrates Java/Spring Boot microservices on AWS. The project covers all key evaluation areas: distributed event-driven architecture, DynamoDB NoSQL data modeling, idempotency patterns, Kafka DLQ chains, ECS deployment, and GitHub Actions CI/CD with OIDC auth.

The directory `/Users/alex/code/sandbox/java/java-spring-boot-aws-microservices` is currently empty.

---

## Tech Stack

| Layer | Choice | Version |
|---|---|---|
| Language | Java | 21 (virtual threads) |
| Framework | Spring Boot | 3.4.3 |
| Cloud | Spring Cloud | 2024.0.1 (Mooregate) |
| NoSQL | DynamoDB (AWS SDK v2) | 2.29.x |
| Messaging | Kafka (spring-kafka) | 3.3.x |
| Resilience | Resilience4j | 2.3.0 |
| Build | Maven multi-module | 3.9.x |
| Container | Docker (multi-stage, Temurin 21 JRE Alpine) | — |
| IaC | Terraform | ≥ 1.9 |
| Deploy | AWS ECS Fargate | — |
| CI/CD | GitHub Actions + OIDC (no long-lived keys) | — |

---

## Project Structure

```
java-spring-boot-aws-microservices/
├── pom.xml                          # Parent POM (packaging=pom)
├── .gitignore
├── mvnw / mvnw.cmd / .mvn/
├── common/                          # Shared event POJOs + ErrorResponse (not a Spring app)
│   ├── pom.xml
│   └── src/main/java/com/orderflow/common/
│       ├── events/
│       │   ├── OrderCreatedEvent.java
│       │   ├── InventoryReservedEvent.java
│       │   ├── InventoryFailedEvent.java
│       │   ├── PaymentSucceededEvent.java
│       │   └── PaymentFailedEvent.java
│       ├── dto/ErrorResponse.java
│       └── config/JacksonConfig.java
├── order-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/orderflow/order/
│       ├── OrderServiceApplication.java
│       ├── config/ (DynamoDbConfig, KafkaConfig, OpenApiConfig)
│       ├── controller/OrderController.java
│       ├── service/ (OrderService, OutboxPublisherService)
│       ├── repository/ (OrderRepository, IdempotencyRepository, OutboxRepository)
│       ├── model/ (Order, IdempotencyRecord, OutboxRecord, OrderStatus)
│       ├── dto/ (CreateOrderRequest, CreateOrderResponse)
│       └── exception/ (GlobalExceptionHandler, DuplicateIdempotencyKeyException, OrderNotFoundException)
├── inventory-service/
│   ├── pom.xml / Dockerfile
│   └── src/main/java/com/orderflow/inventory/
│       ├── config/ (DynamoDbConfig, KafkaConfig, Resilience4jConfig)
│       ├── consumer/OrderCreatedConsumer.java
│       ├── service/InventoryService.java
│       └── repository/ (InventoryRepository, InventoryIdempotencyRepository)
├── payment-service/
│   ├── pom.xml / Dockerfile
│   └── src/main/java/com/orderflow/payment/
│       ├── config/ (KafkaConfig, Resilience4jConfig)
│       ├── consumer/InventoryReservedConsumer.java
│       └── service/PaymentService.java
├── api-gateway/
│   ├── pom.xml / Dockerfile
│   └── src/main/java/com/orderflow/gateway/
│       ├── ApiGatewayApplication.java
│       ├── config/GatewayConfig.java
│       ├── filter/ (ApiKeyAuthFilter, RequestLoggingFilter)
│       └── ratelimit/ApiKeyRateLimitKeyResolver.java
├── docker-compose.yml
├── infra/
│   ├── main.tf / variables.tf / outputs.tf / providers.tf / backend.tf
│   └── modules/ (ecr/, ecs/, iam/, networking/, secrets/)
├── .github/workflows/
│   ├── ci.yml
│   └── cd.yml
└── README.md
```

---

## Key Patterns

### 1. Idempotency via DynamoDB TransactWriteItems

`POST /orders` requires `Idempotency-Key` header. `OrderService` calls `TransactWriteItems` with:
1. `PutItem` → orders table (order record)
2. `PutItem` → order_outbox table (outbox record, status=PENDING)
3. `PutItem` → order_idempotency_keys table with `ConditionExpression: attribute_not_exists(PK)`

If the key already exists, DynamoDB throws `TransactionCanceledException`. The service returns the cached response. All three writes succeed or none do.

### 2. Transactional Outbox Pattern

`OutboxPublisherService` runs `@Scheduled(fixedDelay = 1000)`:
- Queries `GSI_PendingOutbox` for up to 50 PENDING records
- Publishes each to `orders.events` Kafka topic (synchronous `.get(5, SECONDS)`)
- Marks record as PUBLISHED only after confirmed delivery

### 3. Kafka DLQ Chain (@RetryableTopic)

```
orders.events
  → inventory.events-retry (1s, 2s backoff with jitter)
  → inventory.events.dlq   (after 3 attempts)
```

Same pattern for payment-service consuming inventory.events.

`@DltHandler` logs + alerts on DLQ messages.

### 4. Resilience4j

- Circuit breaker on DynamoDB calls (slidingWindow=10, failureThreshold=50%)
- Retry with exponential backoff + 30% jitter (maxAttempts=3, waitDuration=200ms)
- TimeLimiter timeout=3s on DynamoDB calls
- Circuit breaker on payment gateway simulation with fallback method

### 5. Observability

- Structured JSON logging via `logstash-logback-encoder` (plain text in local profile)
- MDC propagation: `orderId`, `correlationId` in every log line
- `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` for distributed traces
- Custom metrics: `orders.created` counter, Kafka consumer lag via actuator

---

## DynamoDB Table Design

### orders
```
PK: "ORDER#<orderId>"  SK: "METADATA"
Attributes: orderId, customerId, items[], totalAmount, status, createdAt, updatedAt, version
GSI: GSI_CustomerOrders (PK=customerId, SK=createdAt) — "get orders by customer"
```

### order_idempotency_keys
```
PK: "IDEMPOTENCY#<idempotencyKey>"
Attributes: idempotencyKey, orderId, cachedResponse (JSON), createdAt, ttl (24h auto-expire)
```

### order_outbox
```
PK: "OUTBOX#<outboxId>"
Attributes: outboxId, aggregateId, eventType, payload (JSON), status, createdAt, publishedAt, retryCount
GSI: GSI_PendingOutbox (PK=status, SK=createdAt) — "get PENDING outbox records ordered by time"
```

### inventory
```
PK: "ITEM#<skuId>"
Attributes: skuId, productName, availableQty, reservedQty, version
Reservation via conditional UpdateItem: SET availableQty = availableQty - :qty IF availableQty >= :qty
```

### inventory_idempotency
```
PK: "PROCESSED#<orderId>"
TTL: 48h auto-expire
```

---

## Kafka Topics

| Topic | Producer | Consumer |
|---|---|---|
| orders.events | order-service (outbox publisher) | inventory-service |
| orders.events-retry / .dlq | Spring Kafka @RetryableTopic | — |
| inventory.events | inventory-service | payment-service |
| inventory.events-retry / .dlq | Spring Kafka @RetryableTopic | — |
| payment.events | payment-service | (final sink, logged) |

---

## docker-compose.yml Services

| Service | Image | Port |
|---|---|---|
| zookeeper | confluentinc/cp-zookeeper:7.7.1 | 2181 |
| kafka | confluentinc/cp-kafka:7.7.1 | 9092 (external), 29092 (internal) |
| kafka-ui | provectuslabs/kafka-ui:latest | 8090 |
| dynamodb-local | amazon/dynamodb-local:2.5.2 | 8000 |
| dynamodb-admin | aaronshaf/dynamodb-admin:latest | 8001 |
| redis | redis:7.4-alpine | 6379 |
| order-service | built | 8081→8080 |
| inventory-service | built | 8082→8080 |
| payment-service | built | 8083→8080 |
| api-gateway | built | 8080→8080 |

All services use healthchecks + `depends_on: condition: service_healthy`.

---

## Dockerfile Pattern (multi-stage, all services)

```
Stage 1 (builder):    eclipse-temurin:21-jdk-alpine → mvn package -DskipTests
Stage 2 (extractor):  eclipse-temurin:21-jdk-alpine → java -Djarmode=layertools extract
Stage 3 (runtime):    eclipse-temurin:21-jre-alpine  → non-root user, copy layers, ZGC flags
```

Build context = project root so the mvnw wrapper is accessible across all service Dockerfiles.

---

## Terraform Modules

```
infra/
├── main.tf           — wires modules together
├── backend.tf        — S3 + DynamoDB remote state
├── providers.tf      — AWS provider ~5.80
└── modules/
    ├── networking/   — VPC (default VPC for demo simplicity)
    ├── ecr/          — 4 ECR repositories (one per service)
    ├── iam/          — ECS task role (DynamoDB access) + execution role + GitHub OIDC role
    ├── ecs/          — ECS Fargate cluster, 4 task definitions + services, ALB, deployment circuit breaker
    └── secrets/      — Secrets Manager for Kafka bootstrap servers + API keys
```

ECS services run in private subnets. ALB in public subnets routes to api-gateway. `deployment_circuit_breaker { enable=true, rollback=true }` for safe deploys.

---

## GitHub Actions

### ci.yml (on PR)
1. `mvn test` — unit tests all modules
2. `mvn verify -Pfailsafe` — integration tests with Testcontainers (Kafka + LocalStack)
3. OWASP dependency-check (failBuildOnCVSS=7)
4. Publish test report as PR check

### cd.yml (on merge to main)
1. OIDC auth → assume `orderflow-github-actions-role` (no long-lived keys)
2. Matrix build (4 services in parallel): JAR → Docker image → push to ECR
3. Matrix deploy (4 services): describe current task def → update image → register new revision → `ecs update-service --force-new-deployment`
4. `aws ecs wait services-stable` to confirm rollout

---

## AWS Prerequisites (One-Time Manual Setup)

### Before running Terraform:

```bash
# 1. Create Terraform state bucket (replace ACCOUNT_ID)
aws s3api create-bucket --bucket orderflow-terraform-state-ACCOUNT_ID --region us-east-1
aws s3api put-bucket-versioning --bucket orderflow-terraform-state-ACCOUNT_ID \
  --versioning-configuration Status=Enabled

# 2. Create DynamoDB state lock table
aws dynamodb create-table \
  --table-name orderflow-terraform-locks \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST --region us-east-1
```

### After terraform apply:

```bash
# 3. Seed inventory data
aws dynamodb put-item --table-name inventory \
  --item '{"PK":{"S":"ITEM#SKU-001"}, "skuId":{"S":"SKU-001"},
           "productName":{"S":"Widget Pro"}, "availableQty":{"N":"100"},
           "reservedQty":{"N":"0"}, "version":{"N":"0"}}'

# 4. Store Kafka bootstrap servers in Secrets Manager
aws secretsmanager update-secret \
  --secret-id orderflow/kafka \
  --secret-string '{"bootstrap_servers":"<your-kafka-host>:9092"}'
```

### GitHub repo setup:
- **Variables** → `AWS_ACCOUNT_ID` = your 12-digit account ID
- **Secrets** → `NVD_API_KEY` (optional, for faster OWASP scans)
- Update `cd.yml` OIDC condition with your GitHub org/repo name

### Kafka choice (two options):
- **Option A (simpler/cheaper)**: EC2 t3.small running docker-compose kafka
- **Option B (realistic)**: Amazon MSK Serverless — pay per usage, zero ops

---

## Implementation Order

### Phase 1 — Foundation (Day 1)
1. Parent `pom.xml` with all 5 modules declared and BOM imports (Spring Boot 3.4.3, Spring Cloud 2024.0.1, AWS SDK v2 2.29.x, Resilience4j 2.3.0)
2. `common` module: event records + `JacksonConfig` + `ErrorResponse`
3. Maven wrapper (`mvnw`) generation
4. Verify `mvn install -pl common` succeeds

### Phase 2 — order-service Core (Days 1-2)
5. `DynamoDbConfig` with endpoint override support
6. DynamoDB model classes (`@DynamoDbBean`)
7. Repository layer with `TransactWriteItems` implementation
8. `CreateOrderRequest` DTO with `@Valid` annotations
9. `OrderService` + `GlobalExceptionHandler`
10. `OrderController` with `Idempotency-Key` header requirement
11. Unit tests: `@WebMvcTest` + service unit tests with mocks

### Phase 3 — Outbox Publisher (Day 2)
12. `KafkaConfig` with JSON serializer
13. `OutboxPublisherService` with `@Scheduled` polling
14. Integration test with Testcontainers (Kafka + LocalStack)

### Phase 4 — inventory-service (Days 2-3)
15. Kafka consumer with `@RetryableTopic` DLQ chain
16. Idempotent `InventoryService` with conditional DynamoDB UpdateItem
17. Resilience4j config (circuit breaker + retry + timeout)
18. Integration test

### Phase 5 — payment-service (Day 3)
19. Consumer for `inventory.events` with `@RetryableTopic`
20. `PaymentService` with circuit breaker + 90% success simulation
21. Integration test

### Phase 6 — api-gateway (Days 3-4)
22. `GatewayConfig` routes + `RedisRateLimiter`
23. `ApiKeyAuthFilter` global pre-filter
24. `ApiKeyRateLimitKeyResolver`
25. Unit test for filter

### Phase 7 — Observability (Day 4)
26. `logback-spring.xml` per service (JSON in non-local profiles)
27. OTel tracing setup (endpoint configurable via env var)
28. Custom Micrometer counters in `OrderService`
29. Actuator health/metrics/prometheus endpoints

### Phase 8 — Docker Compose (Days 4-5)
30. Multi-stage `Dockerfile` for each service
31. `docker-compose.yml` with all infra + services + healthchecks
32. Verify `docker compose up --build` → full end-to-end flow

### Phase 9 — Terraform (Days 5-6)
33. Bootstrap state backend (manual AWS CLI steps above)
34. Write all Terraform modules
35. `terraform init && terraform plan && terraform apply`
36. Verify ECR + ECS cluster in AWS Console

### Phase 10 — CI/CD (Day 6)
37. `ci.yml`: test + verify + OWASP
38. `cd.yml`: OIDC + matrix build + ECR push + ECS deploy
39. Set GitHub repo variables; open test PR; verify full pipeline

### Phase 11 — Polish (Day 7)
40. `README.md` with architecture diagram (Mermaid), quick-start commands, design rationale
41. `scripts/smoke-test.sh`: happy path + duplicate idempotency key test + inventory failure DLQ test
42. `.env.example` listing all required env vars

---

## Interview Talking Points (pre-built answers)

1. **Outbox vs direct Kafka publish** — dual-write problem; outbox = atomic guarantee via DynamoDB transaction
2. **DynamoDB transaction for idempotency** — `attribute_not_exists(PK)` + `TransactWriteItems` = atomic idempotency gate
3. **@RetryableTopic vs SeekToCurrentErrorHandler** — non-blocking retries don't stall the partition; blocking retries do
4. **OIDC vs IAM access keys** — 15-minute token, no stored credentials, scoped to repo+branch
5. **PAY_PER_REQUEST billing** — zero cost at zero traffic, ideal for demo/bursty workloads
6. **Layered Docker images** — dependency layer cached separately; rebuilds only push the changed app layer
