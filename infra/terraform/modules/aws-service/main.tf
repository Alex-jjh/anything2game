data "aws_region" "current" {}
data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" { state = "available" }

locals {
  prefix      = "${var.name}-${var.environment}"
  azs         = slice(data.aws_availability_zones.available.names, 0, 2)
  oidc_issuer = var.enable_auth ? "https://cognito-idp.${data.aws_region.current.region}.amazonaws.com/${aws_cognito_user_pool.app[0].id}" : ""
}
