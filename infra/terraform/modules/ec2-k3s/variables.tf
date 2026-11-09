variable "name" {
  description = "Name prefix for every resource."
  type        = string
}

variable "vpc_id" {
  description = "The VPC the instance runs in."
  type        = string
}

variable "subnet_id" {
  description = "The public subnet the instance runs in."
  type        = string
}

variable "admin_cidr" {
  description = "The one address range allowed to reach the web app and the API: yours, as a /32."
  type        = string

  validation {
    condition     = can(cidrnetmask(var.admin_cidr))
    error_message = "admin_cidr must be a CIDR range, such as 203.0.113.7/32."
  }
  validation {
    # The gate: never open to the internet. A /24 at most; the runbook uses a /32.
    condition     = can(cidrnetmask(var.admin_cidr)) && try(tonumber(split("/", var.admin_cidr)[1]) >= 24, false)
    error_message = "admin_cidr must be your own address (a /32), never a wide range such as 0.0.0.0/0."
  }
}

variable "instance_type" {
  description = "The EC2 instance type. Everything runs on it: k3s, the data stores and nine services."
  type        = string
  default     = "t3.xlarge"
}

variable "root_volume_gb" {
  description = "Size of the encrypted root volume, in GB."
  type        = number
  default     = 30

  validation {
    condition     = var.root_volume_gb >= 20 && var.root_volume_gb <= 100
    error_message = "root_volume_gb must be between 20 and 100."
  }
}

variable "max_hours" {
  description = "The instance terminates itself this many hours after it boots, destroy or not."
  type        = number
  default     = 4

  validation {
    condition     = var.max_hours >= 1 && var.max_hours <= 12 && floor(var.max_hours) == var.max_hours
    error_message = "max_hours must be a whole number of hours from 1 to 12."
  }
}

variable "k3s_version" {
  description = "The k3s release to install: the Kubernetes version kind runs in CI."
  type        = string
  default     = "v1.36.4+k3s1"

  validation {
    condition     = can(regex("^v[0-9]+\\.[0-9]+\\.[0-9]+\\+k3s[0-9]+$", var.k3s_version))
    error_message = "k3s_version must be an exact release, such as v1.36.4+k3s1."
  }
}

variable "git_repository" {
  description = "The public repository the instance checks out to deploy from."
  type        = string
  default     = "https://github.com/Kartavya1905/commerceflow.git"
}

variable "git_commit" {
  description = "The full commit hash to deploy. Images tagged with its first 12 characters must be published."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.git_commit))
    error_message = "git_commit must be a full 40-character commit hash, not a branch or tag."
  }
}

variable "image_registry" {
  description = "Where the images are: <registry>/<owner>/commerceflow, each image below it by service name."
  type        = string
  default     = "ghcr.io/kartavya1905/commerceflow"
}
