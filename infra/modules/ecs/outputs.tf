output "cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "alb_dns_name" {
  description = "Public DNS name of the Application Load Balancer"
  value       = aws_lb.main.dns_name
}

output "ecs_tasks_sg_id" {
  description = "Security group ID of ECS tasks — used by kafka_ec2 module to allow ingress"
  value       = aws_security_group.ecs_tasks.id
}
