# The production-shaped answer (ARCHITECTURE.md K.2): what the demo instance runs itself, as AWS
# managed services. EKS for k3s, RDS for Postgres, MSK for Kafka, ElastiCache for Redis, in
# private subnets across three availability zones.
#
# VALIDATE ONLY. CI checks it (fmt, validate, tflint, checkov, a plan against a mocked provider);
# this project never applies it. Running it would cost money every hour: the EKS control plane,
# a NAT gateway, the nodes, a Multi-AZ database, three Kafka brokers, two cache nodes and a KMS
# key. A plan fails unless acknowledge_hourly_cost is set, so it cannot be applied by accident.

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
      Stack     = "reference-eks"
      ManagedBy = "terraform"
    }
  }
}

resource "terraform_data" "cost_guard" {
  lifecycle {
    precondition {
      condition     = var.acknowledge_hourly_cost
      error_message = "reference-eks is validate-only: applying it costs money every hour (ARCHITECTURE.md K.2). Set acknowledge_hourly_cost = true only if you mean to pay for it."
    }
  }
}

data "aws_caller_identity" "this" {}

# Community modules, pinned to the commit of a release (v6.7.3 and v21.26.0), not to a tag that
# could be moved.
module "vpc" {
  source = "git::https://github.com/terraform-aws-modules/terraform-aws-vpc.git?ref=b3abd6df2ecf052451a361ed55b8f06f8742a795"

  name            = var.name
  cidr            = "10.60.0.0/16"
  azs             = var.availability_zones
  private_subnets = ["10.60.0.0/19", "10.60.32.0/19", "10.60.64.0/19"]
  public_subnets  = ["10.60.96.0/22", "10.60.100.0/22", "10.60.104.0/22"]

  enable_nat_gateway   = true
  single_nat_gateway   = true
  enable_dns_hostnames = true

  public_subnet_tags  = { "kubernetes.io/role/elb" = 1 }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = 1 }
}

# The module lets the nodes reach any address (its egress_all rule): they pull images and reach the
# control plane and AWS APIs through the NAT gateway. A reference stack, never applied.
#trivy:ignore:AWS-0104
module "eks" {
  source = "git::https://github.com/terraform-aws-modules/terraform-aws-eks.git?ref=323c633629b8e4306d94eb1392d7400a80143621"

  name               = var.name
  kubernetes_version = "1.36"
  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.private_subnets

  # The API server is private: operators reach it from inside the VPC.
  endpoint_public_access                   = false
  endpoint_private_access                  = true
  enable_cluster_creator_admin_permissions = true

  eks_managed_node_groups = {
    apps = {
      instance_types = ["m7i.large"]
      min_size       = 3
      max_size       = 6
      desired_size   = 3
    }
  }
}

# --- One customer-managed key for the data stores and their logs ---------------------------------

data "aws_iam_policy_document" "data_key" {
  # A key policy's Resource "*" means this key alone, and the account statement is AWS's own
  # default key policy (without it the key could become unmanageable). Generic IAM checks read
  # both as broad grants; they are not.
  #checkov:skip=CKV_AWS_356:In a key policy, Resource "*" is the key itself
  #checkov:skip=CKV_AWS_109:The account-administers statement is AWS's default key policy
  #checkov:skip=CKV_AWS_111:The account-administers statement is AWS's default key policy

  # The account administers the key, as AWS's default key policy does.
  statement {
    sid       = "AccountAdministers"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.this.account_id}:root"]
    }
  }
  # CloudWatch Logs encrypts the Kafka broker logs with it, for this account's log groups only.
  statement {
    sid = "CloudWatchLogsEncrypts"
    actions = [
      "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey*", "kms:ReEncrypt*",
    ]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["logs.${var.region}.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:aws:logs:${var.region}:${data.aws_caller_identity.this.account_id}:log-group:*"]
    }
  }
}

resource "aws_kms_key" "data" {
  description             = "CommerceFlow reference stack: data stores and their logs"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.data_key.json
}

# --- Data stores: reachable from the cluster's nodes only, encrypted at rest and in transit ------

resource "aws_security_group" "data" {
  name        = "${var.name}-data"
  description = "CommerceFlow data stores: from the EKS nodes only"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "data_from_nodes" {
  for_each = { postgres = 5432, kafka = 9098, redis = 6379 }

  security_group_id            = aws_security_group.data.id
  description                  = "${each.key} from the EKS nodes"
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

resource "aws_db_subnet_group" "postgres" {
  name       = "${var.name}-postgres"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${var.name}-postgres18"
  family = "postgres18"

  # TLS for every connection.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  # Slow statements and schema changes in the database's log, as on Compose (log_lock_waits).
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
  parameter {
    name  = "log_statement"
    value = "ddl"
  }
  parameter {
    name  = "log_lock_waits"
    value = "1"
  }
}

# One server with a database per service, as on Compose and kind (ADR-0006). RDS generates the
# master password and keeps it in Secrets Manager; it never appears here or in the state.
resource "aws_db_instance" "postgres" {
  identifier                          = "${var.name}-postgres"
  engine                              = "postgres"
  engine_version                      = "18"
  instance_class                      = "db.t4g.medium"
  allocated_storage                   = 50
  storage_type                        = "gp3"
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.data.arn
  multi_az                            = true
  db_subnet_group_name                = aws_db_subnet_group.postgres.name
  parameter_group_name                = aws_db_parameter_group.postgres.name
  vpc_security_group_ids              = [aws_security_group.data.id]
  username                            = "commerceflow_admin"
  manage_master_user_password         = true
  iam_database_authentication_enabled = true
  backup_retention_period             = 7
  deletion_protection                 = true
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${var.name}-postgres-final"
  auto_minor_version_upgrade          = true
  copy_tags_to_snapshot               = true
  performance_insights_enabled        = true
  performance_insights_kms_key_id     = aws_kms_key.data.arn
  enabled_cloudwatch_logs_exports     = ["postgresql"]
  monitoring_interval                 = 60
  monitoring_role_arn                 = aws_iam_role.rds_monitoring.arn
}

data "aws_iam_policy_document" "rds_monitoring_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "rds_monitoring" {
  name               = "${var.name}-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.rds_monitoring_trust.json
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# Three brokers, one per zone, replication factor 3 with min.insync.replicas 2: the setting the
# local single broker cannot have (ARCHITECTURE.md F). Clients authenticate with IAM over TLS.
resource "aws_msk_configuration" "kafka" {
  name           = "${var.name}-kafka"
  kafka_versions = [var.kafka_version]
  server_properties = join("\n", [
    "auto.create.topics.enable=false",
    "default.replication.factor=3",
    "min.insync.replicas=2",
  ])
}

resource "aws_msk_cluster" "kafka" {
  cluster_name           = "${var.name}-kafka"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = "kafka.m7g.large"
    client_subnets  = module.vpc.private_subnets
    security_groups = [aws_security_group.data.id]
    storage_info {
      ebs_storage_info {
        volume_size = 100
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.kafka.arn
    revision = aws_msk_configuration.kafka.latest_revision
  }

  client_authentication {
    sasl {
      iam = true
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.data.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.kafka.name
      }
    }
  }
}

resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/commerceflow/${var.name}/kafka"
  retention_in_days = 365
  kms_key_id        = aws_kms_key.data.arn
}

resource "aws_elasticache_subnet_group" "redis" {
  name       = "${var.name}-redis"
  subnet_ids = module.vpc.private_subnets
}

# Clients sign in with IAM through a user group: no static auth token, so none in the state.
resource "aws_elasticache_user" "app" {
  user_id       = "${var.name}-app"
  user_name     = "${var.name}-app"
  engine        = "valkey"
  access_string = "on ~* +@all -@dangerous"
  authentication_mode {
    type = "iam"
  }
}

resource "aws_elasticache_user_group" "app" {
  user_group_id = "${var.name}-app"
  engine        = "valkey"
  user_ids      = [aws_elasticache_user.app.user_id]
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "${var.name}-redis"
  description                = "CommerceFlow carts, catalogue cache and rate limits"
  engine                     = "valkey"
  node_type                  = "cache.t4g.small"
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.redis.name
  security_group_ids         = [aws_security_group.data.id]
  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.data.arn
  transit_encryption_enabled = true
  user_group_ids             = [aws_elasticache_user_group.app.user_group_id]
  auto_minor_version_upgrade = true
  snapshot_retention_limit   = 1

  #checkov:skip=CKV_AWS_31:Authentication is IAM through user_group_ids, not a static auth token kept in the state
}
