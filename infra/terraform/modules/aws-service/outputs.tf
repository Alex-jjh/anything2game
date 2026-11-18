# Same output names in every <cloud>-service module.

output "base_url" {
  description = "Public HTTPS base URL of the API (the app's server base URL)."
  value       = "https://${aws_cloudfront_distribution.edge.domain_name}"
}

output "image_repository" {
  description = "Where to push the server image."
  value       = aws_ecr_repository.server.repository_url
}

output "app_secret_id" {
  description = "Secret holding the server's provider keys (JSON object). Set its value out of band, never in Terraform."
  value       = aws_secretsmanager_secret.app.arn
}

output "oidc_issuer" {
  description = "OIDC issuer for optional sign-in (empty when auth is disabled)."
  value       = var.enable_auth ? local.oidc_issuer : ""
}

output "oidc_client_id" {
  description = "Public OAuth client id for the app (empty when auth is disabled)."
  value       = var.enable_auth ? aws_cognito_user_pool_client.app[0].id : ""
}

output "auth_domain" {
  description = "Hosted sign-in domain (empty when auth is disabled)."
  value       = var.enable_auth ? "https://${aws_cognito_user_pool_domain.app[0].domain}.auth.${data.aws_region.current.region}.amazoncognito.com" : ""
}

output "logs" {
  description = "Where the server's logs go."
  value       = aws_cloudwatch_log_group.server.name
}
