output "state_bucket" {
  description = "For the repository variable AWS_STATE_BUCKET."
  value       = aws_s3_bucket.state.id
}

output "deploy_role_arn" {
  description = "For the repository variable AWS_DEPLOY_ROLE_ARN."
  value       = module.github_oidc.deploy_role_arn
}

output "watcher_role_arn" {
  description = "For the repository variable AWS_WATCHER_ROLE_ARN."
  value       = module.github_oidc.watcher_role_arn
}
