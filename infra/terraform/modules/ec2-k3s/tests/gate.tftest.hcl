# The Milestone 11 gate, for the demo instance, as plans against a mocked AWS provider: no
# credentials, no account, no cost (scripts/check-terraform.sh).

mock_provider "aws" {
  # A plausible image id, so the instance's ami argument looks like one.
  mock_data "aws_ssm_parameter" {
    defaults = {
      insecure_value = "ami-0123456789abcdef0"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

variables {
  name       = "commerceflow-demo"
  vpc_id     = "vpc-0123456789abcdef0"
  subnet_id  = "subnet-0123456789abcdef0"
  admin_cidr = "203.0.113.7/32"
  git_commit = "0123456789abcdef0123456789abcdef01234567"
}

run "only_the_operator_reaches_the_two_app_ports" {
  command = plan

  assert {
    condition     = aws_vpc_security_group_ingress_rule.web.cidr_ipv4 == "203.0.113.7/32" && aws_vpc_security_group_ingress_rule.api.cidr_ipv4 == "203.0.113.7/32"
    error_message = "Ingress must come from admin_cidr only."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.web.from_port == 30000 && aws_vpc_security_group_ingress_rule.web.to_port == 30000
    error_message = "The web rule must open NodePort 30000 alone."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.api.from_port == 30080 && aws_vpc_security_group_ingress_rule.api.to_port == 30080
    error_message = "The API rule must open NodePort 30080 alone."
  }
  # These two are the module's only ingress rules: no port 22, as there is no key pair either
  # (administration is through Session Manager). check-terraform.sh also fails on any port 22.
}

run "the_open_internet_is_refused" {
  command = plan
  variables {
    admin_cidr = "0.0.0.0/0"
  }
  expect_failures = [var.admin_cidr]
}

run "a_wide_range_is_refused" {
  command = plan
  variables {
    admin_cidr = "10.0.0.0/8"
  }
  expect_failures = [var.admin_cidr]
}

run "metadata_disk_and_cost_safeguards" {
  command = plan

  assert {
    condition     = aws_instance.k3s.metadata_options[0].http_tokens == "required" && aws_instance.k3s.metadata_options[0].http_put_response_hop_limit == 1
    error_message = "IMDSv2 only, with a hop limit of 1, so pods cannot reach the instance's credentials."
  }
  assert {
    condition     = aws_instance.k3s.root_block_device[0].encrypted && aws_instance.k3s.root_block_device[0].delete_on_termination
    error_message = "The root volume must be encrypted and deleted with the instance."
  }
  assert {
    condition     = aws_instance.k3s.instance_initiated_shutdown_behavior == "terminate"
    error_message = "Shutting down must terminate the instance, so the self-termination timer ends all billing."
  }
  assert {
    condition     = aws_instance.k3s.credit_specification[0].cpu_credits == "standard"
    error_message = "Standard CPU credits: bursts are throttled, never billed."
  }
  assert {
    condition     = !aws_instance.k3s.monitoring
    error_message = "Detailed monitoring is billed; it stays off."
  }
}

run "the_instance_terminates_itself" {
  command = plan
  variables {
    max_hours = 3
  }

  assert {
    condition     = strcontains(aws_instance.k3s.user_data, "shutdown -h \"+$((3 * 60))\"")
    error_message = "The user data must schedule the shutdown that terminates the instance after max_hours."
  }
  assert {
    # The timer comes before anything that could fail and stop the script.
    condition     = strcontains(split("apt-get", aws_instance.k3s.user_data)[0], "shutdown -h")
    error_message = "The self-termination must be scheduled before the first step that can fail."
  }
}

run "no_secret_in_the_user_data" {
  command = plan

  assert {
    condition     = !strcontains(aws_instance.k3s.user_data, "PRIVATE KEY") && !can(regex("PASSWORD=\\S", aws_instance.k3s.user_data))
    error_message = "Secrets are generated on the instance; none may be in the user data (or the state)."
  }
  assert {
    condition     = strcontains(aws_instance.k3s.user_data, "git fetch -q --depth 1 \"https://github.com/Kartavya1905/commerceflow.git\" \"0123456789abcdef0123456789abcdef01234567\"")
    error_message = "The instance must deploy the exact commit it was given."
  }
  assert {
    condition     = strcontains(aws_instance.k3s.user_data, "IMAGE_TAG=\"0123456789ab\"")
    error_message = "The image tag must be the commit's first 12 characters, as kind-up and the publish job tag them."
  }
}

run "a_branch_name_is_not_a_commit" {
  command = plan
  variables {
    git_commit = "main"
  }
  expect_failures = [var.git_commit]
}
