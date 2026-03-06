module "networking" {
  source = "./modules/networking"
}

module "ecr" {
  source      = "./modules/ecr"
  environment = var.environment
}

module "iam" {
  source      = "./modules/iam"
  environment = var.environment
  aws_region  = var.aws_region
  github_org  = var.github_org
  github_repo = var.github_repo
}

module "secrets" {
  source      = "./modules/secrets"
  environment = var.environment
}

module "ecs" {
  source = "./modules/ecs"

  environment         = var.environment
  aws_region          = var.aws_region
  vpc_id              = module.networking.vpc_id
  public_subnet_ids   = module.networking.subnet_ids
  private_subnet_ids  = module.networking.subnet_ids   # default VPC: all subnets are public
  ecr_repository_urls = module.ecr.repository_urls
  task_role_arn       = module.iam.ecs_task_role_arn
  execution_role_arn  = module.iam.ecs_execution_role_arn
  kafka_secret_arn    = module.secrets.kafka_secret_arn
  api_keys_secret_arn = module.secrets.api_keys_secret_arn
  desired_count       = var.ecs_desired_count
}
