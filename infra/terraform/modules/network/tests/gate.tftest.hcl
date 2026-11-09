# The demo network stays small and cheap: no NAT gateway, no addresses by default (scripts/check-terraform.sh).

mock_provider "aws" {}

variables {
  name              = "commerceflow-demo"
  availability_zone = "us-east-1a"
}

run "one_public_subnet_and_nothing_billed_by_the_hour" {
  command = plan

  assert {
    condition     = !aws_subnet.public.map_public_ip_on_launch
    error_message = "Addresses are given per instance, never by default."
  }
  assert {
    condition     = aws_route.internet.destination_cidr_block == "0.0.0.0/0" && aws_route.internet.nat_gateway_id == null
    error_message = "The route out is the internet gateway; there is no NAT gateway (about $0.045 an hour)."
  }
}

run "the_range_must_be_a_cidr" {
  command = plan
  variables {
    cidr_block = "10.42.0.0"
  }
  expect_failures = [var.cidr_block]
}
