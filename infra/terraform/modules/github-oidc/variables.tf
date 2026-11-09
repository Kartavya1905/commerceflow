variable "github_repository" {
  description = "The repository whose workflows may assume the roles, as owner/name."
  type        = string
  default     = "Kartavya1905/commerceflow"

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be owner/name."
  }
}

variable "deploy_environment" {
  description = "The GitHub Environment (with a required reviewer) that alone may assume the deploy role."
  type        = string
  default     = "aws-demo"
}

variable "name_prefix" {
  description = "The prefix of every resource the deploy role may manage, and of the roles themselves."
  type        = string
  default     = "commerceflow-demo"
}

variable "project_tag" {
  description = "The Project tag every managed resource carries; the deploy role's EC2 rights depend on it."
  type        = string
  default     = "commerceflow"
}

variable "region" {
  description = "The one region the deploy role may act in."
  type        = string
}

variable "state_bucket_arn" {
  description = "The bucket holding the demo's Terraform state."
  type        = string
}

variable "state_key_prefix" {
  description = "The key prefix, in the state bucket, of the demo's state and lock files."
  type        = string
  default     = "demo-ec2/"
}
