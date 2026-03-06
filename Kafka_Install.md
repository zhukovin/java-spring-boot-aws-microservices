# Kafka 4.2.0 Installation on EC2 t3.micro

## Step 1 — Launch the EC2 instance

1. AWS Console → **EC2** → **Launch instance**

| Field | Value |
|---|---|
| Name | `orderflow-kafka` |
| AMI | **Amazon Linux 2023** |
| Instance type | `t3.micro` |
| Key pair | Create new → name it `orderflow-kafka-key` → download the `.pem` file |
| VPC | Default VPC (same as your ECS cluster) |
| Auto-assign public IP | **Enable** (so you can SSH in) |

2. Under **Firewall (Security group)** → Create new security group named `orderflow-kafka-sg` with these inbound rules:

| Type | Port | Source | Purpose |
|---|---|---|---|
| SSH | 22 | My IP | your access |
| Custom TCP | 9092 | `orderflow-dev-tasks` SG ID | ECS tasks → Kafka |

Click **Launch instance**.

---

## Step 2 — SSH into the instance

```bash
chmod 400 orderflow-kafka-key.pem

# Get the public IP from the EC2 console
EC2_IP=<public-ip-of-instance>

ssh -i orderflow-kafka-key.pem ec2-user@${EC2_IP}
```

---

## Step 3 — Install Java

```bash
sudo dnf install -y java-21-amazon-corretto-headless
java -version
# Expected: openjdk version "21..."
```

---

## Step 4 — Download and install Kafka

```bash
KAFKA_VERSION=4.2.0

wget -q "https://downloads.apache.org/kafka/${KAFKA_VERSION}/kafka_2.13-${KAFKA_VERSION}.tgz"
tar -xzf "kafka_2.13-${KAFKA_VERSION}.tgz"
sudo mv "kafka_2.13-${KAFKA_VERSION}" /opt/kafka
rm "kafka_2.13-${KAFKA_VERSION}.tgz"
```

---

## Step 5 — Configure Kafka

```bash
# Generate a cluster UUID
KAFKA_UUID=$(/opt/kafka/bin/kafka-storage.sh random-uuid)
echo "Cluster UUID: ${KAFKA_UUID}"

# Get the instance's private IP
PRIVATE_IP=$(hostname -I | awk '{print $1}')
echo "Private IP: ${PRIVATE_IP}"
```

Write the config:

```bash
sudo tee /opt/kafka/config/server.properties > /dev/null <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@localhost:9093
listeners=PLAINTEXT://${PRIVATE_IP}:9092,CONTROLLER://localhost:9093
advertised.listeners=PLAINTEXT://${PRIVATE_IP}:9092
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT
log.dirs=/opt/kafka/data
num.partitions=3
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
EOF
```

Format the storage:

```bash
sudo mkdir -p /opt/kafka/data
sudo chown ec2-user:ec2-user /opt/kafka/data

/opt/kafka/bin/kafka-storage.sh format \
  -t "${KAFKA_UUID}" \
  -c /opt/kafka/config/server.properties
```

---

## Step 6 — Create a systemd service so Kafka survives reboots

```bash
sudo tee /etc/systemd/system/kafka.service > /dev/null <<EOF
[Unit]
Description=Apache Kafka
After=network.target

[Service]
User=ec2-user
Environment="KAFKA_HEAP_OPTS=-Xmx512m -Xms256m"
ExecStart=/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/server.properties
ExecStop=/opt/kafka/bin/kafka-server-stop.sh
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable kafka
sudo systemctl start kafka
```

**Verify Kafka is running:**
```bash
sudo systemctl status kafka
# Expected: active (running)

ss -tlnp | grep 9092
# Expected: a line showing LISTEN on <private-ip>:9092
```

---

## Step 7 — Create the required topics

```bash
PRIVATE_IP=$(hostname -I | awk '{print $1}')

for TOPIC in orders.events inventory.events payment.events; do
  /opt/kafka/bin/kafka-topics.sh --create \
    --topic "${TOPIC}" \
    --bootstrap-server "${PRIVATE_IP}:9092" \
    --partitions 3 \
    --replication-factor 1
done
```

**Verify:**
```bash
/opt/kafka/bin/kafka-topics.sh --list --bootstrap-server "${PRIVATE_IP}:9092"
# Expected: orders.events, inventory.events, payment.events
```

---

## Step 8 — Get the bootstrap string for AWS_Deploy.md Step 4.1

```bash
PRIVATE_IP=$(hostname -I | awk '{print $1}')
echo "KAFKA_ENDPOINT=${PRIVATE_IP}:9092"
```

Use this value when updating the Secrets Manager secret in Step 4.1 of `AWS_Deploy.md`.
