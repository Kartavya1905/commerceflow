output "instance_id" {
  description = "The instance's id, for Session Manager."
  value       = aws_instance.k3s.id
}

output "public_ip" {
  description = "The instance's public address."
  value       = aws_instance.k3s.public_ip
}

output "web_url" {
  description = "The web app, reachable from admin_cidr only."
  value       = "http://${aws_instance.k3s.public_ip}:30000"
}

output "api_url" {
  description = "The API gateway, reachable from admin_cidr only."
  value       = "http://${aws_instance.k3s.public_ip}:30080"
}

output "session_command" {
  description = "A shell on the instance, without SSH."
  value       = "aws ssm start-session --target ${aws_instance.k3s.id}"
}
