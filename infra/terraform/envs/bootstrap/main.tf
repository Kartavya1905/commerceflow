# Created once, by the AWS account's owner, before the demo can be deployed (runbooks/aws-demo.md):
# - the bucket that holds the demo's Terraform state (encrypted, versioned, private, TLS only);
# - GitHub's OIDC provider, and the deploy and watcher roles (modules/github-oidc).
#
# Run it from AWS CloudShell, which is free and already signed in, so no access key is ever
# created. Its own state stays local there: it holds resource ids and role ARNs, no secrets, and
# changes rarely.

terraform {
  required_version = ">= 1.16.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.68.0"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Project   = "commerceflow"
      Stack     = "bootstrap"
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "this" {}

resource "aws_s3_bucket" "state" {
  # Bucket names are global: the account id keeps this one unique.
  bucket = "commerceflow-tfstate-${data.aws_caller_identity.this.account_id}"

  #checkov:skip=CKV_AWS_144:Cross-region replication doubles the cost of a few kilobytes of state
  #checkov:skip=CKV_AWS_18:Access logging needs a second bucket; CloudTrail records the API calls
  #checkov:skip=CKV2_AWS_62:Nothing needs to react to a state write
  #checkov:skip=CKV_AWS_145:A customer-managed KMS key costs $1 a month; S3-managed keys (AES256) encrypt it
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      # S3-managed keys: free. A customer-managed KMS key costs $1 a month.
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    id     = "keep-30-days-of-history"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "state_tls_only" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state_tls_only.json
}

module "github_oidc" {
  source           = "../../modules/github-oidc"
  region           = var.region
  state_bucket_arn = aws_s3_bucket.state.arn
}
