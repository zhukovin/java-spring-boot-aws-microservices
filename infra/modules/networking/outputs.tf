output "vpc_id" {
  description = "VPC ID"
  value       = data.aws_vpc.default.id
}

output "subnet_ids" {
  description = "All subnet IDs in the default VPC"
  value       = data.aws_subnets.all.ids
}
