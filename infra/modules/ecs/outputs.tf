output "cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "alb_dns_name" {
  description = "Public DNS name of the Application Load Balancer"
  value       = aws_lb.main.dns_name
}
