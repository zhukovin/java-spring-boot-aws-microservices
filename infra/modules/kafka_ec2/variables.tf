variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "ecs_tasks_sg_id" {
  description = "Security group ID of ECS tasks — allowed to connect to Kafka and Redis"
  type        = string
}

variable "environment" {
  type = string
}

variable "ec2_public_key" {
  description = "SSH public key material for the EC2 key pair"
  type        = string
}
