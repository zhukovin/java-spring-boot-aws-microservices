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
