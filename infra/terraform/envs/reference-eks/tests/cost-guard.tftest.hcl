# reference-eks is validate-only: a plan must fail unless the cost is acknowledged.

# The EKS module checks some values it reads (an ARN, a policy's JSON), so the mocks give
# plausible ones.
mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:role/reference"
    }
  }
  mock_data "aws_iam_session_context" {
    defaults = {
      issuer_arn = "arn:aws:iam::123456789012:role/reference"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition  = "aws"
      dns_suffix = "amazonaws.com"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}
mock_provider "tls" {}
mock_provider "time" {}
mock_provider "null" {}
mock_provider "cloudinit" {}

run "a_plan_fails_without_the_acknowledgement" {
  command         = plan
  expect_failures = [terraform_data.cost_guard]
}

run "and_passes_the_guard_only_with_it" {
  command = plan
  variables {
    acknowledge_hourly_cost = true
  }
}
