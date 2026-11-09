output "deploy_role_arn" {
  description = "For the repository variable AWS_DEPLOY_ROLE_ARN."
  value       = aws_iam_role.deploy.arn
}

output "watcher_role_arn" {
  description = "For the repository variable AWS_WATCHER_ROLE_ARN."
  value       = aws_iam_role.watcher.arn
}
