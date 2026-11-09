# The demo's network: one VPC, one public subnet, an internet gateway and a route to it.
#
# Deliberately small (ARCHITECTURE.md K.2): no NAT gateway, no load balancer, no private subnets.
# The one instance has a public address, and its security group admits only the operator's
# address; administration is through SSM Session Manager, so no port is open for SSH. Every
# resource carries the Project tag (the provider's default_tags), which the deploy role's rights
# depend on.

resource "aws_vpc" "this" {
  cidr_block           = var.cidr_block
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = var.name }

  # Flow logs are billed (CloudWatch Logs or S3) for a network that exists for a few hours,
  # holds one instance and admits one address.
  #checkov:skip=CKV2_AWS_11:Flow logs cost money; the demo VPC lives a few hours and admits one address
  # The default security group is left as AWS creates it. Nothing is ever placed in it (the
  # instance has its own group), and emptying it would mean letting the deploy role change an
  # untagged resource, which would let it change any security group in the account.
  #checkov:skip=CKV2_AWS_12:Nothing uses the default group; managing it would widen the deploy role
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = var.name }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(var.cidr_block, 8, 0)
  availability_zone = var.availability_zone
  # Addresses are given per instance (associate_public_ip_address), never by default.
  map_public_ip_on_launch = false

  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-public" }
}

resource "aws_route" "internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}
