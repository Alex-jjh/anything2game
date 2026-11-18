resource "aws_ecr_repository" "server" {
  name                 = "${local.prefix}-server"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
}

resource "aws_ecr_lifecycle_policy" "server" {
  repository = aws_ecr_repository.server.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 10 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

# One secret (JSON object) for the server's provider keys, e.g. {"A2G_COMPILE_API_KEY": "..."}.
# Terraform creates the container only; the value is set out of band (see infra/README.md), so keys never enter state.
resource "aws_secretsmanager_secret" "app" {
  name                    = "${local.prefix}/server"
  description             = "Anything2Game server provider keys (JSON). Value set outside Terraform."
  recovery_window_in_days = 7
}

resource "aws_cloudwatch_log_group" "server" {
  name              = "/${var.name}/${var.environment}/server"
  retention_in_days = var.log_retention_days
}
