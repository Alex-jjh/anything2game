resource "random_password" "origin_verify" {
  length  = 40
  special = false
}

resource "aws_ecs_cluster" "main" {
  name = local.prefix
}

locals {
  # The container contract (server/README.md): everything the server needs arrives as env vars.
  server_env = merge(
    {
      A2G_HOST           = "0.0.0.0" # in-container bind; reachability is decided by the security groups above
      A2G_PORT           = tostring(var.container_port)
      A2G_STORE          = "postgres"
      A2G_DB_URL         = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/a2g?sslmode=require"
      A2G_DB_USER        = "a2g"
      A2G_LOG_FORMAT     = "json"
      A2G_OIDC_ISSUER    = local.oidc_issuer
      A2G_OIDC_CLIENT_ID = var.enable_auth ? aws_cognito_user_pool_client.app[0].id : ""
    },
    var.env,
  )
  server_secrets = concat(
    [{ name = "A2G_DB_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" }],
    [for k in var.secret_keys : { name = k, valueFrom = "${aws_secretsmanager_secret.app.arn}:${k}::" }],
  )
}

resource "aws_ecs_task_definition" "server" {
  family                   = "${local.prefix}-server"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "server"
    image        = "${aws_ecr_repository.server.repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = var.container_port, protocol = "tcp" }]
    environment  = [for k, v in local.server_env : { name = k, value = v } if v != ""]
    secrets      = local.server_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.server.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "server"
      }
    }
  }])
}

# Internal ALB: no public address at all. CloudFront reaches it through a VPC origin (edge.tf).
resource "aws_lb" "main" {
  name                       = local.prefix
  load_balancer_type         = "application"
  internal                   = true
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.private[*].id
  drop_invalid_header_fields = true
  idle_timeout               = 120
}

resource "aws_lb_target_group" "server" {
  name                 = local.prefix
  port                 = var.container_port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    path                = "/v1/ready"
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# HTTP inside the VPC only (CloudFront VPC origin → internal ALB). Viewer traffic is HTTPS at CloudFront.
#trivy:ignore:AVD-AWS-0054 -- internal listener behind a CloudFront VPC origin; TLS terminates at CloudFront. Add an ACM cert + https-only origin once a domain exists.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  # Anything that did not come through our CloudFront distribution is refused.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Use the HTTPS endpoint.\",\"retryable\":false}}"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "from_cloudfront" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10
  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [random_password.origin_verify.result]
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.server.arn
  }
}

resource "aws_ecs_service" "server" {
  name                               = "server"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.server.arn
  desired_count                      = var.running ? var.min_instances : 0
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 90
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.service.id]
    assign_public_ip = true # outbound only (no NAT gateway); inbound is ALB-only by security group
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.server.arn
    container_name   = "server"
    container_port   = var.container_port
  }
  depends_on = [aws_lb_listener_rule.from_cloudfront]
}
