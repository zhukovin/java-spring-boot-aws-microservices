output "ecr_repository_urls" {
  description = "ECR repository URLs keyed by service name"
  value       = module.ecr.repository_urls
}

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = module.ecs.cluster_name
}

output "alb_dns_name" {
  description = "ALB DNS name — the public entry point for the API"
  value       = module.ecs.alb_dns_name
}

output "github_actions_role_arn" {
  description = "IAM role ARN to set as the GitHub Actions OIDC role"
  value       = module.iam.github_actions_role_arn
}

output "kafka_ec2_private_ip" {
  description = "Private IP of the Kafka+Redis EC2 instance"
  value       = module.kafka_ec2.private_ip
}

output "kafka_ec2_instance_id" {
  description = "EC2 instance ID — used by deploy.sh to poll readiness via SSM"
  value       = module.kafka_ec2.instance_id
}
