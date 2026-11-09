# GitHub Actions signs in to AWS with short-lived OIDC tokens: no access keys exist (ADR-0016).
#
# Two roles, each for one job:
# - deploy: assumable only from the repository's aws-demo Environment, which needs a reviewer's
#   approval for every run. It may apply and destroy envs/demo-ec2 and nothing else: every action
#   named (never "*"), EC2 changes limited to one region and to resources tagged with the
#   project, IAM limited to the demo's own role and instance profile, and the only policy it can
#   attach is Session Manager's, so it cannot grant itself more.
# - watcher: assumable from the main branch, read-only. The scheduled check uses it to find any
#   demo resource still running.

data "aws_caller_identity" "this" {}
data "aws_partition" "this" {}

locals {
  account   = data.aws_caller_identity.this.account_id
  partition = data.aws_partition.this.partition
  issuer    = "token.actions.githubusercontent.com"
  ssm_core  = "arn:${local.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://${local.issuer}"
  client_id_list = ["sts.amazonaws.com"]
}

# --- Trust: who may become each role --------------------------------------------------------------

data "aws_iam_policy_document" "trust_deploy" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.issuer}:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Only a job running in the approved Environment of this repository.
    condition {
      test     = "StringEquals"
      variable = "${local.issuer}:sub"
      values   = ["repo:${var.github_repository}:environment:${var.deploy_environment}"]
    }
  }
}

data "aws_iam_policy_document" "trust_watcher" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.issuer}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.issuer}:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

# --- What the deploy role may do ------------------------------------------------------------------

data "aws_iam_policy_document" "deploy" {
  # Reads that AWS cannot scope to a resource.
  statement {
    sid = "Describe"
    actions = [
      "ec2:DescribeAccountAttributes",
      "ec2:DescribeImages",
      "ec2:DescribeInstanceAttribute",
      "ec2:DescribeInstanceCreditSpecifications",
      "ec2:DescribeInstanceTypes",
      "ec2:DescribeInstances",
      "ec2:DescribeInternetGateways",
      "ec2:DescribeNetworkAcls",
      "ec2:DescribeNetworkInterfaces",
      "ec2:DescribeRouteTables",
      "ec2:DescribeSecurityGroupRules",
      "ec2:DescribeSecurityGroups",
      "ec2:DescribeSubnets",
      "ec2:DescribeTags",
      "ec2:DescribeVolumes",
      "ec2:DescribeVpcAttribute",
      "ec2:DescribeVpcs",
    ]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }

  # Creating: only with the project's tag on the new resource, in the one region.
  statement {
    sid = "CreateTagged"
    actions = [
      "ec2:CreateInternetGateway",
      "ec2:CreateRouteTable",
      "ec2:CreateSecurityGroup",
      "ec2:CreateSubnet",
      "ec2:CreateVpc",
      "ec2:RunInstances",
    ]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/Project"
      values   = [var.project_tag]
    }
  }

  # RunInstances also touches the image, the subnet, the group and the volume, which it does not
  # create: allowed for the AMI publisher's images and the project's own network.
  statement {
    sid       = "RunInstancesFromCanonicalImages"
    actions   = ["ec2:RunInstances"]
    resources = ["arn:${local.partition}:ec2:${var.region}::image/*"]
    condition {
      test     = "StringEquals"
      variable = "ec2:Owner"
      values   = ["099720109477"] # Canonical
    }
  }
  statement {
    sid     = "RunInstancesInProjectNetwork"
    actions = ["ec2:RunInstances"]
    resources = [
      "arn:${local.partition}:ec2:${var.region}:${local.account}:subnet/*",
      "arn:${local.partition}:ec2:${var.region}:${local.account}:security-group/*",
    ]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/Project"
      values   = [var.project_tag]
    }
  }
  statement {
    sid     = "RunInstancesNewParts"
    actions = ["ec2:RunInstances"]
    resources = [
      "arn:${local.partition}:ec2:${var.region}:${local.account}:volume/*",
      "arn:${local.partition}:ec2:${var.region}:${local.account}:network-interface/*",
    ]
  }

  # Tagging, only as part of one of the creations above.
  statement {
    sid       = "TagOnCreate"
    actions   = ["ec2:CreateTags"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "ec2:CreateAction"
      values = [
        "CreateInternetGateway", "CreateRouteTable", "CreateSecurityGroup", "CreateSubnet",
        "CreateVpc", "RunInstances",
      ]
    }
  }

  # Changing and deleting: only resources that carry the project's tag.
  statement {
    sid = "ChangeTagged"
    actions = [
      "ec2:AssociateRouteTable",
      "ec2:AttachInternetGateway",
      "ec2:AuthorizeSecurityGroupEgress",
      "ec2:AuthorizeSecurityGroupIngress",
      "ec2:CreateRoute",
      "ec2:DeleteInternetGateway",
      "ec2:DeleteRoute",
      "ec2:DeleteRouteTable",
      "ec2:DeleteSecurityGroup",
      "ec2:DeleteSubnet",
      "ec2:DeleteTags",
      "ec2:DeleteVpc",
      "ec2:DetachInternetGateway",
      "ec2:DisassociateRouteTable",
      "ec2:ModifyInstanceAttribute",
      "ec2:ModifySubnetAttribute",
      "ec2:ModifyVpcAttribute",
      "ec2:RevokeSecurityGroupEgress",
      "ec2:RevokeSecurityGroupIngress",
      "ec2:TerminateInstances",
    ]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/Project"
      values   = [var.project_tag]
    }
  }

  # The instance's role and profile: only the demo's own names.
  statement {
    sid = "InstanceRole"
    actions = [
      "iam:AddRoleToInstanceProfile",
      "iam:CreateInstanceProfile",
      "iam:CreateRole",
      "iam:DeleteInstanceProfile",
      "iam:DeleteRole",
      "iam:GetInstanceProfile",
      "iam:GetRole",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole",
      "iam:ListRolePolicies",
      "iam:RemoveRoleFromInstanceProfile",
      "iam:TagInstanceProfile",
      "iam:TagRole",
    ]
    resources = [
      "arn:${local.partition}:iam::${local.account}:role/${var.name_prefix}-*",
      "arn:${local.partition}:iam::${local.account}:instance-profile/${var.name_prefix}-*",
    ]
  }

  # The one policy it may attach: Session Manager's. Without this condition it could attach
  # AdministratorAccess to a role it then passes to an instance.
  statement {
    sid       = "AttachSessionManagerOnly"
    actions   = ["iam:AttachRolePolicy", "iam:DetachRolePolicy"]
    resources = ["arn:${local.partition}:iam::${local.account}:role/${var.name_prefix}-*"]
    condition {
      test     = "ArnEquals"
      variable = "iam:PolicyARN"
      values   = [local.ssm_core]
    }
  }

  statement {
    sid       = "PassInstanceRoleToEc2"
    actions   = ["iam:PassRole"]
    resources = ["arn:${local.partition}:iam::${local.account}:role/${var.name_prefix}-*"]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }

  # The Ubuntu image's id, from Canonical's public parameter.
  statement {
    sid       = "UbuntuImageParameter"
    actions   = ["ssm:GetParameter"]
    resources = ["arn:${local.partition}:ssm:${var.region}::parameter/aws/service/canonical/*"]
  }

  # The demo's state and its lock file, and nothing else in the bucket.
  statement {
    sid       = "StateObjects"
    actions   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
    resources = ["${var.state_bucket_arn}/${var.state_key_prefix}*"]
  }
  statement {
    sid       = "StateBucketList"
    actions   = ["s3:ListBucket"]
    resources = [var.state_bucket_arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.state_key_prefix}*"]
    }
  }
}

data "aws_iam_policy_document" "watcher" {
  statement {
    sid       = "FindRunningDemo"
    actions   = ["ec2:DescribeInstances", "ec2:DescribeVolumes"]
    resources = ["*"]
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.name_prefix}-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.trust_deploy.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "deploy" {
  name   = "apply-and-destroy-demo-ec2"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}

resource "aws_iam_role" "watcher" {
  name                 = "${var.name_prefix}-github-watcher"
  assume_role_policy   = data.aws_iam_policy_document.trust_watcher.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "watcher" {
  name   = "find-running-demo"
  role   = aws_iam_role.watcher.id
  policy = data.aws_iam_policy_document.watcher.json
}
