variable "name" {
  description = "Name prefix for every resource."
  type        = string
}

variable "cidr_block" {
  description = "The VPC's address range."
  type        = string
  default     = "10.42.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.cidr_block))
    error_message = "cidr_block must be a CIDR range such as 10.42.0.0/16."
  }
}

variable "availability_zone" {
  description = "The availability zone of the one subnet."
  type        = string
}
