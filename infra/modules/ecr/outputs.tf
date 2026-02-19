output "repository_urls" {
  description = "Map of service name → ECR repository URL"
  value = {
    for name, repo in aws_ecr_repository.services :
    name => repo.repository_url
  }
}

output "repository_arns" {
  description = "Map of service name → ECR repository ARN (for IAM policies)"
  value = {
    for name, repo in aws_ecr_repository.services :
    name => repo.arn
  }
}
