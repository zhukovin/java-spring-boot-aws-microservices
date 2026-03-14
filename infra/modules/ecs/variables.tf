variable "environment"         { type = string }
variable "aws_region"          { type = string }
variable "vpc_id"              { type = string }
variable "public_subnet_ids"   { type = list(string) }
variable "private_subnet_ids"  { type = list(string) }
variable "task_role_arn"       { type = string }
variable "execution_role_arn"  { type = string }
variable "kafka_secret_arn"    { type = string }
variable "api_keys_secret_arn" { type = string }
variable "kafka_ec2_private_ip" {
  description = "Private IP of the Kafka+Redis EC2 instance — injected as REDIS_HOST into api-gateway"
  type        = string
}
variable "desired_count" {
  type    = number
  default = 1
}

variable "ecr_repository_urls" {
  description = "Map of service name to ECR repository URL"
  type        = map(string)
}
