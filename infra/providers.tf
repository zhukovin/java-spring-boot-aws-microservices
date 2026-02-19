terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # Default tags applied to every resource created by Terraform
  default_tags {
    tags = {
      Project     = "orderflow"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
