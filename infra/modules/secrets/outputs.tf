output "kafka_secret_arn" {
  value = aws_secretsmanager_secret.kafka.arn
}

output "api_keys_secret_arn" {
  value = aws_secretsmanager_secret.api_keys.arn
}
