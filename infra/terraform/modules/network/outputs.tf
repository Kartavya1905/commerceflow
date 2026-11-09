output "vpc_id" {
  description = "The VPC's id."
  value       = aws_vpc.this.id
}

output "public_subnet_id" {
  description = "The public subnet's id."
  value       = aws_subnet.public.id
}
