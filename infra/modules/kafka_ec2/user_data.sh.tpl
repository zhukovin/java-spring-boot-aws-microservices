#!/usr/bin/env bash
# cloud-init user_data — runs once on first boot.
# Installs Java 21, Kafka 4.2.0 (KRaft), Redis 6, creates topics,
# and writes a sentinel file when ready.
set -euo pipefail

KAFKA_VERSION="4.0.0"
KAFKA_DIR="/opt/kafka"
SENTINEL="/var/lib/orderflow/kafka_ready"
TOPICS="orders.events inventory.events payment.events"

log() { echo "[orderflow-init] $*" | tee -a /var/log/orderflow-init.log; }

mkdir -p /var/lib/orderflow

log "Installing Java 21..."
dnf install -y java-21-amazon-corretto-headless

log "Installing Redis 6..."
dnf install -y redis6
sed -i 's/^bind 127.0.0.1/bind 0.0.0.0/' /etc/redis6/redis6.conf
systemctl enable redis6
systemctl start redis6

log "Downloading Kafka ${KAFKA_VERSION}..."
curl -fsSL "https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/kafka_2.13-${KAFKA_VERSION}.tgz" \
  -o /tmp/kafka.tgz
tar -xzf /tmp/kafka.tgz -C /opt
mv "/opt/kafka_2.13-${KAFKA_VERSION}" "${KAFKA_DIR}"
rm /tmp/kafka.tgz

log "Configuring Kafka..."
# Get private IP via IMDSv2
TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
PRIVATE_IP=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/local-ipv4)

KAFKA_UUID=$("${KAFKA_DIR}/bin/kafka-storage.sh" random-uuid)

cat > "${KAFKA_DIR}/config/server.properties" <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@localhost:9093
listeners=PLAINTEXT://${PRIVATE_IP}:9092,CONTROLLER://localhost:9093
advertised.listeners=PLAINTEXT://${PRIVATE_IP}:9092
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT
log.dirs=${KAFKA_DIR}/data
num.partitions=3
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
EOF

mkdir -p "${KAFKA_DIR}/data"
"${KAFKA_DIR}/bin/kafka-storage.sh" format \
  -t "${KAFKA_UUID}" \
  -c "${KAFKA_DIR}/config/server.properties"

log "Creating Kafka systemd service..."
cat > /etc/systemd/system/kafka.service <<EOF
[Unit]
Description=Apache Kafka
After=network.target

[Service]
User=root
Environment="KAFKA_HEAP_OPTS=-Xmx512m -Xms256m"
ExecStart=${KAFKA_DIR}/bin/kafka-server-start.sh ${KAFKA_DIR}/config/server.properties
ExecStop=${KAFKA_DIR}/bin/kafka-server-stop.sh
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable kafka
systemctl start kafka

log "Waiting for Kafka to be ready..."
for i in $(seq 1 30); do
  if "${KAFKA_DIR}/bin/kafka-topics.sh" --list \
      --bootstrap-server "${PRIVATE_IP}:9092" &>/dev/null; then
    log "Kafka is up."
    break
  fi
  log "  Attempt $i/30 — waiting..."
  sleep 5
done

log "Creating Kafka topics..."
for TOPIC in ${TOPICS}; do
  "${KAFKA_DIR}/bin/kafka-topics.sh" --create \
    --topic "${TOPIC}" \
    --bootstrap-server "${PRIVATE_IP}:9092" \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists
  log "  Topic ready: ${TOPIC}"
done

log "All done."
echo "ready" > "${SENTINEL}"
