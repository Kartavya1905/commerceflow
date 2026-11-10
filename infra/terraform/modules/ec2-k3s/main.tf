# One EC2 instance running k3s, with CommerceFlow and its data stores on it (ARCHITECTURE.md K.2,
# ADR-0016).
#
# - Reachable only from admin_cidr, and only on the web app's and the gateway's NodePorts.
# - No SSH: no key pair and no port 22. An operator gets a shell through SSM Session Manager,
#   which the instance's role allows, and nothing else.
# - IMDSv2 only, with a hop limit of 1, so pods cannot reach the instance's credentials.
# - The root volume is encrypted and deleted with the instance.
# - It terminates itself max_hours after boot (bootstrap.sh.tftpl), so a forgotten demo stops
#   costing money even if nobody runs terraform destroy.
# - Secrets are generated on the instance at boot and never leave it: none are in this
#   configuration, its state, or the user data.

data "aws_ssm_parameter" "ubuntu" {
  # Canonical's current Ubuntu 24.04 LTS image, published through a public SSM parameter.
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
}

data "aws_iam_policy_document" "assume_ec2" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name               = "${var.name}-instance"
  assume_role_policy = data.aws_iam_policy_document.assume_ec2.json
}

# Session Manager and nothing else: the images come from a public registry, and the instance reads
# no other AWS service.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "instance" {
  name = "${var.name}-instance"
  role = aws_iam_role.instance.name
}

resource "aws_security_group" "instance" {
  name        = "${var.name}-instance"
  description = "CommerceFlow demo: the web app and the API, from the operator's address only"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.name}-instance" }
}

resource "aws_vpc_security_group_ingress_rule" "web" {
  security_group_id = aws_security_group.instance.id
  description       = "Web app (NodePort 30000), from the operator only"
  cidr_ipv4         = var.admin_cidr
  ip_protocol       = "tcp"
  from_port         = 30000
  to_port           = 30000
}

resource "aws_vpc_security_group_ingress_rule" "api" {
  security_group_id = aws_security_group.instance.id
  description       = "API gateway (NodePort 30080), from the operator only"
  cidr_ipv4         = var.admin_cidr
  ip_protocol       = "tcp"
  from_port         = 30080
  to_port           = 30080
}

# Out: HTTPS for k3s, the repository, the image registry and Session Manager, and HTTP for Ubuntu's
# package mirrors. DNS and time go to the VPC's own resolver, which security groups do not filter.
# Any destination, on these two ports only: GitHub, GHCR, Ubuntu's mirrors and SSM publish no
# fixed address ranges, and VPC endpoints for SSM would be billed by the hour.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "https" {
  security_group_id = aws_security_group.instance.id
  description       = "HTTPS: k3s, GitHub, GHCR and SSM"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "http" {
  security_group_id = aws_security_group.instance.id
  description       = "HTTP: Ubuntu package mirrors"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_instance" "k3s" {
  ami                         = data.aws_ssm_parameter.ubuntu.insecure_value
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [aws_security_group.instance.id]
  iam_instance_profile        = aws_iam_instance_profile.instance.name
  associate_public_ip_address = true
  ebs_optimized               = true
  # Detailed monitoring is billed per metric; the demo has kubectl and the services' own metrics.
  monitoring = false
  # The self-termination timer shuts the instance down, and shutting down terminates it.
  instance_initiated_shutdown_behavior = "terminate"

  # Standard credits: a burst beyond the baseline is throttled rather than billed, so the cost is
  # the hourly price and nothing more (unlimited, the t3 default, charges for sustained bursts).
  credit_specification {
    cpu_credits = "standard"
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gb
    encrypted             = true
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/bootstrap.sh.tftpl", {
    max_hours      = var.max_hours
    k3s_version    = var.k3s_version
    git_repository = var.git_repository
    git_commit     = var.git_commit
    # The tag scripts/kind-up.sh and the publish workflow give an image: the commit's first 12.
    image_tag      = substr(var.git_commit, 0, 12)
    image_registry = var.image_registry
  })
  user_data_replace_on_change = true

  tags = { Name = var.name }

  #checkov:skip=CKV_AWS_126:Detailed monitoring is billed per metric; the demo lives a few hours
  #checkov:skip=CKV_AWS_88:The instance needs a public address; its security group admits one /32
}
