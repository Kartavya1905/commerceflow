# The demo environment: one EC2 instance running k3s and all of CommerceFlow, in its own VPC
# (ARCHITECTURE.md K.2, ADR-0016). Nothing here is applied unless the owner runs the aws-demo
# workflow, which needs a reviewer's approval (runbooks/aws-demo.md); CI only validates it.
#
# While it runs it costs about $0.18 an hour (t3.xlarge, 30 GB of gp3, one public IPv4 address,
# us-east-1 prices of 2026-11-03), and nothing once destroyed. The instance terminates itself
# after max_hours whether or not anyone runs destroy.

terraform {
  required_version = ">= 1.16.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.68.0"
    }
  }

  # State in the bootstrap stack's bucket, locked with S3's own lock file. The bucket and region
  # are given at init (-backend-config), so no account details are committed.
  backend "s3" {
    key          = "demo-ec2/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      # The deploy role may change only resources with this tag (modules/github-oidc).
      Project   = "commerceflow"
      Stack     = "demo-ec2"
      ManagedBy = "terraform"
    }
  }
}

module "network" {
  source            = "../../modules/network"
  name              = var.name
  availability_zone = var.availability_zone
}

module "k3s" {
  source         = "../../modules/ec2-k3s"
  name           = var.name
  vpc_id         = module.network.vpc_id
  subnet_id      = module.network.public_subnet_id
  admin_cidr     = var.admin_cidr
  instance_type  = var.instance_type
  max_hours      = var.max_hours
  git_commit     = var.git_commit
  image_registry = var.image_registry
}
