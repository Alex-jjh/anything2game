terraform {
  required_version = ">= 1.10"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Local state for the student demo. For a shared team state, switch to S3 with native locking:
  # backend "s3" { bucket = "<state-bucket>"  key = "a2g/demo/terraform.tfstate"  region = "ap-southeast-1"  use_lockfile = true }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "anything2game", Environment = var.environment, ManagedBy = "terraform" }
  }
}

# CloudFront-scoped resources (the edge WAF) must be created in us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
  default_tags {
    tags = { Project = "anything2game", Environment = var.environment, ManagedBy = "terraform" }
  }
}

# Switching cloud = switching this source (e.g. ../../modules/gcp-service) — inputs and outputs are the same.
module "service" {
  source    = "../../modules/aws-service"
  providers = { aws = aws, aws.us_east_1 = aws.us_east_1 }

  name                = "a2g"
  environment         = var.environment
  image_tag           = var.image_tag
  min_instances       = 1
  running             = var.running
  deletion_protection = var.deletion_protection
  enable_auth         = true
  budget_email        = var.budget_email
  budget_limit_usd    = var.budget_limit_usd

  # Model endpoints are configuration, not infrastructure: change them here (or in the secret) without code changes.
  env         = var.server_env
  secret_keys = var.secret_keys
}
