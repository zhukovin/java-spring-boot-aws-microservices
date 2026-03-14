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

module "dynamodb" {
  source = "./modules/dynamodb"
}

module "kafka_ec2" {
  source = "./modules/kafka_ec2"

  vpc_id          = module.networking.vpc_id
  subnet_id       = module.networking.subnet_ids[0]
  ecs_tasks_sg_id = module.ecs.ecs_tasks_sg_id
  environment     = var.environment
  ec2_public_key  = var.ec2_public_key
}

module "ecs" {
  source = "./modules/ecs"

  environment          = var.environment
  aws_region           = var.aws_region
  vpc_id               = module.networking.vpc_id
  public_subnet_ids    = module.networking.subnet_ids
  private_subnet_ids   = module.networking.subnet_ids
  ecr_repository_urls  = module.ecr.repository_urls
  task_role_arn        = module.iam.ecs_task_role_arn
  execution_role_arn   = module.iam.ecs_execution_role_arn
  kafka_secret_arn     = module.secrets.kafka_secret_arn
  api_keys_secret_arn  = module.secrets.api_keys_secret_arn
  kafka_ec2_private_ip = module.kafka_ec2.private_ip
  desired_count        = var.ecs_desired_count
}
