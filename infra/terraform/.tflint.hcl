# tflint for every stack and module (scripts/check-terraform.sh): Terraform's recommended rules,
# plus the AWS ruleset (invalid instance types, deprecated arguments and the like).
config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "aws" {
  enabled = true
  version = "0.49.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}
