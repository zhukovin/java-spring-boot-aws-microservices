variable "environment" {
  type = string
}

# Kafka bootstrap servers — update after your Kafka cluster is provisioned
resource "aws_secretsmanager_secret" "kafka" {
  name                    = "orderflow/kafka"
  recovery_window_in_days = 0  # Allow immediate deletion for demo
}

resource "aws_secretsmanager_secret_version" "kafka" {
  secret_id = aws_secretsmanager_secret.kafka.id
  secret_string = jsonencode({
    bootstrap_servers = "PLACEHOLDER — update with MSK or EC2 Kafka endpoint"
  })
}

# API keys for the gateway — update with real keys before deploying
resource "aws_secretsmanager_secret" "api_keys" {
  name                    = "orderflow/api-keys"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "api_keys" {
  secret_id = aws_secretsmanager_secret.api_keys.id
  secret_string = jsonencode({
    api_keys = "PLACEHOLDER — set production API keys here"
  })
}
