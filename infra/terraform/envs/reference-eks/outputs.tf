output "cluster_name" {
  description = "The EKS cluster."
  value       = module.eks.cluster_name
}

output "postgres_endpoint" {
  description = "The RDS endpoint, for the endpoints ConfigMap of an EKS overlay."
  value       = aws_db_instance.postgres.address
}

output "kafka_bootstrap_brokers" {
  description = "The MSK brokers (IAM over TLS), for the endpoints ConfigMap."
  value       = aws_msk_cluster.kafka.bootstrap_brokers_sasl_iam
}

output "redis_endpoint" {
  description = "The ElastiCache primary endpoint, for the endpoints ConfigMap."
  value       = aws_elasticache_replication_group.redis.primary_endpoint_address
}
