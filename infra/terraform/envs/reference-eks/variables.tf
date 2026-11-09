variable "acknowledge_hourly_cost" {
  description = "Must be true for a plan to succeed. This stack is validate-only; applying it costs money every hour."
  type        = bool
  default     = false
}

variable "region" {
  description = "The region to run in."
  type        = string
  default     = "us-east-1"
}

variable "availability_zones" {
  description = "The three zones, named rather than looked up, so a plan never changes because AWS added one."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b", "us-east-1c"]

  validation {
    condition     = length(var.availability_zones) == 3
    error_message = "Three zones: one per subnet pair, broker and database replica."
  }
}

variable "name" {
  description = "Name prefix for every resource."
  type        = string
  default     = "commerceflow-ref"
}

variable "kafka_version" {
  description = "An MSK Kafka version; check `aws kafka list-kafka-versions` before any apply."
  type        = string
  default     = "3.9.x"
}
