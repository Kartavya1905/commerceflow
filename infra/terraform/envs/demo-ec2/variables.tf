variable "region" {
  description = "The region to run in; the deploy role allows only the bootstrap stack's."
  type        = string
  default     = "us-east-1"
}

variable "availability_zone" {
  description = "The zone of the one subnet, named rather than looked up, so a plan never changes because AWS added a zone."
  type        = string
  default     = "us-east-1a"
}

variable "name" {
  description = "Name prefix for every resource; the deploy role manages only this prefix."
  type        = string
  default     = "commerceflow-demo"
}

variable "admin_cidr" {
  description = "Your address as a /32: the only one allowed to reach the demo."
  type        = string
}

variable "git_commit" {
  description = "The full commit hash to deploy; its images must be published."
  type        = string
}

variable "instance_type" {
  description = "The EC2 instance type."
  type        = string
  default     = "t3.xlarge"
}

variable "max_hours" {
  description = "Hours after boot at which the instance terminates itself."
  type        = number
  default     = 4
}

variable "image_registry" {
  description = "Where the images are."
  type        = string
  default     = "ghcr.io/kartavya1905/commerceflow"
}
