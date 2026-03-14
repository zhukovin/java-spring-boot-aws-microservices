output "private_ip" {
  description = "Private IP of the Kafka+Redis EC2 instance"
  value       = aws_instance.kafka.private_ip
}

output "instance_id" {
  description = "EC2 instance ID — used by deploy.sh to poll readiness via SSM"
  value       = aws_instance.kafka.id
}

output "security_group_id" {
  description = "Security group ID of the Kafka EC2 instance"
  value       = aws_security_group.kafka_ec2.id
}
