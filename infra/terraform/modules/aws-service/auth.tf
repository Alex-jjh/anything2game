# Optional sign-in (ADR-007 keeps anonymous play as the default; signing in adds cross-device sync later).
# Standard OIDC: the server verifies Cognito access tokens via JWKS with no AWS SDK, so the same server accepts
# any OIDC issuer (Google Identity Platform, Firebase Auth, …) by changing A2G_OIDC_ISSUER / A2G_OIDC_CLIENT_ID.

resource "random_id" "auth_domain" {
  count       = var.enable_auth ? 1 : 0
  byte_length = 3
}

resource "aws_cognito_user_pool" "app" {
  count                    = var.enable_auth ? 1 : 0
  name                     = local.prefix
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  deletion_protection      = var.deletion_protection ? "ACTIVE" : "INACTIVE"
  mfa_configuration        = "OFF"

  password_policy {
    minimum_length    = 10
    require_lowercase = true
    require_numbers   = true
    require_symbols   = false
    require_uppercase = false
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }
}

resource "aws_cognito_user_pool_client" "app" {
  count                                = var.enable_auth ? 1 : 0
  name                                 = "${local.prefix}-android"
  user_pool_id                         = aws_cognito_user_pool.app[0].id
  generate_secret                      = false # public mobile client: Authorization Code + PKCE, no secret in the APK
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  callback_urls                        = var.auth_redirect_uris
  logout_urls                          = var.auth_redirect_uris
  supported_identity_providers         = ["COGNITO"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH", "ALLOW_USER_SRP_AUTH"]
  prevent_user_existence_errors        = "ENABLED"
  enable_token_revocation              = true
  access_token_validity                = 60
  id_token_validity                    = 60
  refresh_token_validity               = 30
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

resource "aws_cognito_user_pool_domain" "app" {
  count        = var.enable_auth ? 1 : 0
  domain       = "${local.prefix}-${random_id.auth_domain[0].hex}"
  user_pool_id = aws_cognito_user_pool.app[0].id
}
