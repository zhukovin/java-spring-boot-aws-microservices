variable "environment" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "github_org" {
  description = "GitHub org/username for OIDC subject condition"
  type        = string
}

variable "github_repo" {
  description = "GitHub repository name for OIDC subject condition"
  type        = string
}
