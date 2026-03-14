# ── AMI — latest Amazon Linux 2023 x86_64 ─────────────────────────────────────

data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# ── SSH Key Pair ───────────────────────────────────────────────────────────────

resource "aws_key_pair" "kafka" {
  key_name   = "orderflow-kafka-${var.environment}"
  public_key = var.ec2_public_key
}

# ── IAM Role for SSM (no SSH required) ────────────────────────────────────────

resource "aws_iam_role" "kafka_ec2" {
  name = "orderflow-${var.environment}-kafka-ec2"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "kafka_ssm" {
  role       = aws_iam_role.kafka_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "kafka_ec2" {
  name = "orderflow-${var.environment}-kafka-ec2"
  role = aws_iam_role.kafka_ec2.name
}

# ── Security Group ─────────────────────────────────────────────────────────────

resource "aws_security_group" "kafka_ec2" {
  name   = "orderflow-${var.environment}-kafka-ec2"
  vpc_id = var.vpc_id

  # Kafka — from ECS tasks only
  ingress {
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [var.ecs_tasks_sg_id]
  }

  # Redis — from ECS tasks only
  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.ecs_tasks_sg_id]
  }

  # SSH — for emergency manual access
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ── EC2 Instance ───────────────────────────────────────────────────────────────

resource "aws_instance" "kafka" {
  ami                         = data.aws_ami.amazon_linux_2023.id
  instance_type               = "t3.micro"
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [aws_security_group.kafka_ec2.id]
  key_name                    = aws_key_pair.kafka.key_name
  iam_instance_profile        = aws_iam_instance_profile.kafka_ec2.name
  associate_public_ip_address = true

  user_data = file("${path.module}/user_data.sh.tpl")

  # Increase root volume slightly to fit Kafka + Java
  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }

  tags = {
    Name        = "orderflow-kafka"
    Environment = var.environment
  }
}
