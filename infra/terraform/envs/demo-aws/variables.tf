variable "region" {
  description = "AWS region. Singapore by default (no opt-in, reachable from mainland China). ap-east-1 (Hong Kong) needs opt-in."
  type        = string
  default     = "ap-southeast-1"
}

variable "environment" {
  type    = string
  default = "demo"
}

variable "image_tag" {
  description = "Server image tag pushed by infra/scripts/push-image.sh."
  type        = string
}

variable "deletion_protection" {
  description = "Keep true unless you are deliberately tearing the demo down."
  type        = bool
  default     = true
}

variable "budget_email" {
  type    = string
  default = ""
}

variable "budget_limit_usd" {
  type    = number
  default = 60
}

variable "running" {
  description = "Set false between demos to scale the server to 0."
  type        = bool
  default     = true
}

variable "server_env" {
  description = "Non-secret server settings (providers, base URLs, models)."
  type        = map(string)
  default = {
    A2G_COMPILE_PROVIDER = "fake"
    A2G_FRAME_PROVIDER   = "fake"
    A2G_FLASH_PROVIDER   = "fake"
    A2G_DEFAULT_FRAMES   = "case"
  }
}

variable "secret_keys" {
  description = "Secret env var names read from the server secret (JSON keys of the same name)."
  type        = list(string)
  default     = []
}
