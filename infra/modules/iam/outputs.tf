output "ecs_task_role_arn" {
  value = aws_iam_role.ecs_task.arn
}

output "ecs_execution_role_arn" {
  value = aws_iam_role.ecs_execution.arn
}

output "github_actions_role_arn" {
  description = "Copy this ARN into GitHub repo variable AWS_ROLE_ARN"
  value       = aws_iam_role.github_actions.arn
}
