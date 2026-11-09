output "web_url" {
  description = "The web app, from admin_cidr only."
  value       = module.k3s.web_url
}

output "api_url" {
  description = "The API gateway, from admin_cidr only."
  value       = module.k3s.api_url
}

output "session_command" {
  description = "A shell on the instance, through Session Manager."
  value       = module.k3s.session_command
}

output "instance_id" {
  description = "The instance's id."
  value       = module.k3s.instance_id
}
