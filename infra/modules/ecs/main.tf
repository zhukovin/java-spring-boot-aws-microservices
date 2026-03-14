locals {
  backend_services = ["order-service", "inventory-service", "payment-service"]
  cluster_name     = "orderflow-${var.environment}"
}

# ── ECS Cluster ───────────────────────────────────────────────────────────────

resource "aws_ecs_cluster" "main" {
  name = local.cluster_name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# ── Service Connect Namespace ─────────────────────────────────────────────────
# Gives ECS services stable DNS names within the cluster.
# api-gateway reaches order-service at http://order-service:8080 — no IPs, no patching.

resource "aws_service_discovery_private_dns_namespace" "orderflow" {
  name        = "orderflow.local"
  vpc         = var.vpc_id
  description = "ECS Service Connect namespace for orderflow services"
}

# ── CloudWatch Log Groups ─────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "services" {
  for_each          = toset(concat(local.backend_services, ["api-gateway"]))
  name              = "/ecs/orderflow/${each.key}"
  retention_in_days = 7
}

# ── Security Groups ───────────────────────────────────────────────────────────

resource "aws_security_group" "alb" {
  name   = "${local.cluster_name}-alb"
  vpc_id = var.vpc_id

  ingress {
    from_port   = 80
    to_port     = 80
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

resource "aws_security_group" "ecs_tasks" {
  name   = "${local.cluster_name}-tasks"
  vpc_id = var.vpc_id

  # Accept traffic from the ALB
  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  # Allow inter-service calls via Service Connect
  ingress {
    from_port = 8080
    to_port   = 8080
    protocol  = "tcp"
    self      = true
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ── Application Load Balancer ─────────────────────────────────────────────────

resource "aws_lb" "main" {
  name               = local.cluster_name
  internal           = false
  load_balancer_type = "application"
  subnets            = var.public_subnet_ids
  security_groups    = [aws_security_group.alb.id]
}

resource "aws_lb_target_group" "api_gateway" {
  name        = "${local.cluster_name}-gw"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api_gateway.arn
  }
}

# ── Task Definitions — backend services (order, inventory, payment) ────────────

resource "aws_ecs_task_definition" "backend" {
  for_each = toset(local.backend_services)

  family                   = "orderflow-${each.key}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([{
    name  = each.key
    image = "${var.ecr_repository_urls[each.key]}:latest"

    portMappings = [{
      name          = "http"
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = var.environment }
    ]

    secrets = [
      {
        name      = "KAFKA_BOOTSTRAP_SERVERS"
        valueFrom = "${var.kafka_secret_arn}:bootstrap_servers::"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = "/ecs/orderflow/${each.key}"
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1"]
      interval    = 30
      timeout     = 10
      retries     = 3
      startPeriod = 60
    }
  }])
}

# ── Task Definition — api-gateway (separate: extra env vars + secrets) ─────────

resource "aws_ecs_task_definition" "api_gateway" {
  family                   = "orderflow-api-gateway"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([{
    name  = "api-gateway"
    image = "${var.ecr_repository_urls["api-gateway"]}:latest"

    portMappings = [{
      name          = "http"
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = var.environment },
      # Service Connect gives order-service a stable DNS name — no IP patching needed
      { name = "ORDER_SERVICE_URL",       value = "http://order-service:8080" },
      { name = "REDIS_HOST",              value = var.kafka_ec2_private_ip }
    ]

    secrets = [
      {
        name      = "KAFKA_BOOTSTRAP_SERVERS"
        valueFrom = "${var.kafka_secret_arn}:bootstrap_servers::"
      },
      {
        name      = "APP_API_KEYS"
        valueFrom = "${var.api_keys_secret_arn}:api_keys::"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = "/ecs/orderflow/api-gateway"
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1"]
      interval    = 30
      timeout     = 10
      retries     = 3
      startPeriod = 60
    }
  }])
}

# ── ECS Services — backend ────────────────────────────────────────────────────

resource "aws_ecs_service" "backend" {
  for_each = toset(local.backend_services)

  name            = "orderflow-${each.key}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend[each.key].arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = true
  }

  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_private_dns_namespace.orderflow.arn

    # order-service exposes itself so api-gateway can reach it by DNS name
    dynamic "service" {
      for_each = each.key == "order-service" ? [1] : []
      content {
        port_name      = "http"
        discovery_name = "order-service"
        client_alias {
          dns_name = "order-service"
          port     = 8080
        }
      }
    }
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  deployment_controller {
    type = "ECS"
  }
}

# ── ECS Service — api-gateway ─────────────────────────────────────────────────

resource "aws_ecs_service" "api_gateway" {
  name            = "orderflow-api-gateway"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api_gateway.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api_gateway.arn
    container_name   = "api-gateway"
    container_port   = 8080
  }

  # Client-only Service Connect — can call order-service by DNS, not exposed itself
  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_private_dns_namespace.orderflow.arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  deployment_controller {
    type = "ECS"
  }

  depends_on = [aws_lb_listener.http]
}
