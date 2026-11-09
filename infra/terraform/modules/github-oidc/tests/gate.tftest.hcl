# The Milestone 11 gate, for the GitHub roles: least privilege as plans against a mocked AWS
# provider (scripts/check-terraform.sh). The policy documents' statements are configuration, so
# these assertions read exactly what would be sent to AWS.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

variables {
  region           = "us-east-1"
  state_bucket_arn = "arn:aws:s3:::commerceflow-tfstate-123456789012"
}

run "no_wildcard_actions" {
  command = plan

  assert {
    condition = alltrue(flatten([
      for doc in [data.aws_iam_policy_document.deploy, data.aws_iam_policy_document.watcher] : [
        for s in doc.statement : [for a in s.actions : !strcontains(a, "*")]
      ]
    ]))
    error_message = "Every action must be named; no \"*\" and no \"service:*\"."
  }
}

run "only_the_approved_environment_may_deploy" {
  command = plan

  assert {
    condition = contains(flatten([
      for c in data.aws_iam_policy_document.trust_deploy.statement[0].condition : c.values if c.variable == "token.actions.githubusercontent.com:sub"
    ]), "repo:Kartavya1905/commerceflow:environment:aws-demo")
    error_message = "The deploy role must trust the aws-demo Environment of this repository only."
  }
  assert {
    condition = length(flatten([
      for c in data.aws_iam_policy_document.trust_deploy.statement[0].condition : c.values if c.variable == "token.actions.githubusercontent.com:sub"
    ])) == 1
    error_message = "The deploy role must trust exactly one subject."
  }
  assert {
    condition     = aws_iam_role.deploy.max_session_duration == 3600
    error_message = "Sessions last an hour at most."
  }
}

run "the_deploy_role_cannot_grant_itself_more" {
  command = plan

  assert {
    # The only IAM statement that attaches policies is limited to Session Manager's.
    condition = alltrue([
      for s in data.aws_iam_policy_document.deploy.statement :
      contains(flatten([for c in s.condition : c.values if c.variable == "iam:PolicyARN"]), "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore")
      if contains(s.actions, "iam:AttachRolePolicy")
    ])
    error_message = "iam:AttachRolePolicy must be limited to AmazonSSMManagedInstanceCore."
  }
  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.deploy.statement :
      alltrue([for r in s.resources : strcontains(r, ":role/commerceflow-demo-")])
      if contains(s.actions, "iam:PassRole")
    ])
    error_message = "iam:PassRole must be limited to the demo's own roles."
  }
  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.deploy.statement :
      alltrue([for r in s.resources : r != "*"])
      if anytrue([for a in s.actions : startswith(a, "iam:") || startswith(a, "s3:") || startswith(a, "ssm:")])
    ])
    error_message = "IAM, S3 and SSM rights must name their resources."
  }
}

run "ec2_changes_need_the_project_tag" {
  command = plan

  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.deploy.statement :
      contains(flatten([for c in s.condition : c.values if c.variable == "aws:ResourceTag/Project"]), "commerceflow")
      if anytrue([for a in s.actions : can(regex("^ec2:(Delete|Modify|Terminate|Revoke|Authorize|Attach|Detach|Associate|Disassociate)", a))])
    ])
    error_message = "Every EC2 change or deletion must require the resource's Project tag."
  }
  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.deploy.statement :
      contains(flatten([for c in s.condition : c.values if c.variable == "aws:RequestTag/Project"]), "commerceflow")
      if s.sid == "CreateTagged"
    ])
    error_message = "Creating EC2 resources must require the Project tag on them."
  }
}

run "the_watcher_only_reads" {
  command = plan

  assert {
    condition = alltrue(flatten([
      for s in data.aws_iam_policy_document.watcher.statement : [for a in s.actions : startswith(a, "ec2:Describe")]
    ]))
    error_message = "The watcher role must only describe."
  }
}
