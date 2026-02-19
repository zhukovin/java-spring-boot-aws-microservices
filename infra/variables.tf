variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment (dev | staging | prod)"
  type        = string
  default     = "dev"
}

variable "github_org" {
  description = "GitHub organisation / username — used in the OIDC trust policy"
  type        = string
}

variable "github_repo" {
  description = "GitHub repository name — used in the OIDC trust policy"
  type        = string
  default     = "java-spring-boot-aws-microservices"
}

variable "ecs_desired_count" {
  description = "Desired task count per ECS service"
  type        = number
  default     = 1
}
