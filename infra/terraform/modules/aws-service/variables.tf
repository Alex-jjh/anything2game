# ---------------------------------------------------------------------------------------------------------------------
# Module interface. Every <cloud>-service module (aws-service, gcp-service, …) takes the SAME inputs and returns the
# SAME outputs, so an environment switches cloud by switching the module source. Cloud-only knobs are grouped last.
# ---------------------------------------------------------------------------------------------------------------------

variable "name" {
  description = "Short service name, used as a prefix for every resource (lowercase, digits, hyphens)."
  type        = string
  default     = "a2g"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.name))
    error_message = "name must be 2-21 chars: lowercase letters, digits, hyphens; start with a letter."
  }
}

variable "environment" {
  description = "Environment label, e.g. demo, staging, prod."
  type        = string
}

variable "image_tag" {
  description = "Tag of the server image in this module's registry (e.g. a git SHA). Push it before the first apply of the service."
  type        = string
}

variable "container_port" {
  description = "Port the container listens on (the server reads PORT / A2G_PORT)."
  type        = number
  default     = 8080
}

variable "cpu" {
  description = "vCPU units for one instance (Fargate: 256 = 0.25 vCPU)."
  type        = number
  default     = 512
}

variable "memory" {
  description = "Memory in MiB for one instance."
  type        = number
  default     = 1024
}

variable "min_instances" {
  description = "Instances always running. At least 1: compile jobs run in the background and need CPU between requests."
  type        = number
  default     = 1
  validation {
    condition     = var.min_instances >= 1
    error_message = "min_instances must be >= 1 (background compile jobs need an always-on instance)."
  }
}

variable "env" {
  description = "Plain (non-secret) environment variables for the server, e.g. model names, base URLs, frame defaults."
  type        = map(string)
  default     = {}
}

variable "secret_keys" {
  description = "Names of secret env vars the server needs (e.g. A2G_COMPILE_API_KEY). Each is read from the JSON key of the same name in the module's secret."
  type        = list(string)
  default     = []
}

variable "db_tier" {
  description = "Database size class (AWS: RDS instance class)."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_storage_gb" {
  description = "Database storage in GB."
  type        = number
  default     = 20
}

variable "deletion_protection" {
  description = "Protect the database (and user pool) from deletion. Keep true for anything holding real user data."
  type        = bool
  default     = true
}

variable "enable_auth" {
  description = "Create the identity provider for optional sign-in (AWS: Cognito user pool + public mobile client)."
  type        = bool
  default     = true
}

variable "auth_redirect_uris" {
  description = "OAuth redirect URIs of the app (custom scheme), e.g. com.anything2game.app://auth/callback."
  type        = list(string)
  default     = ["com.anything2game.app://auth/callback"]
}

variable "budget_email" {
  description = "Email for monthly cost alerts. Empty disables the budget."
  type        = string
  default     = ""
}

variable "budget_limit_usd" {
  description = "Monthly budget in USD; alerts at 80% actual and 100% forecast."
  type        = number
  default     = 60
}

# ---- AWS-only knobs ----------------------------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR of the service VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "db_engine_version" {
  description = "PostgreSQL major version for RDS."
  type        = string
  default     = "17"
}

variable "log_retention_days" {
  description = "CloudWatch log retention."
  type        = number
  default     = 14
}

variable "enable_waf" {
  description = "Attach a WAF (per-IP rate limit + IP reputation) to the edge. About USD 7/month."
  type        = bool
  default     = true
}

variable "waf_requests_per_5min" {
  description = "Requests per IP per 5 minutes before the WAF blocks."
  type        = number
  default     = 600
}

variable "enable_flow_logs" {
  description = "VPC flow logs (rejected traffic) to CloudWatch."
  type        = bool
  default     = false
}

variable "running" {
  description = "false scales the service to 0 tasks between demos (the database keeps the data). Saves ~USD 0.6/day."
  type        = bool
  default     = true
}
