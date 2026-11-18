# HTTPS front door without owning a domain: CloudFront's default *.cloudfront.net certificate. No caching — this is an
# API. CloudFront reaches the INTERNAL ALB through a VPC origin, so the ALB has no public address; the secret header is
# defence in depth. With a domain: ACM certificate on the ALB + https-only origin (infra/README.md, "Upgrade path").

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

resource "aws_cloudfront_vpc_origin" "alb" {
  vpc_origin_endpoint_config {
    name                   = "${local.prefix}-alb"
    arn                    = aws_lb.main.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"
    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

#trivy:ignore:AVD-AWS-0010 -- access logs are off for the demo (cost, and they would record client IPs); server logs carry request ids.
resource "aws_cloudfront_distribution" "edge" {
  enabled         = true
  comment         = "${local.prefix} API"
  http_version    = "http2and3"
  price_class     = "PriceClass_200" # includes Asia
  is_ipv6_enabled = true

  origin {
    origin_id   = "alb"
    domain_name = aws_lb.main.dns_name
    vpc_origin_config {
      vpc_origin_id            = aws_cloudfront_vpc_origin.alb.id
      origin_read_timeout      = 60
      origin_keepalive_timeout = 60
    }
    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.origin_verify.result
    }
  }

  default_cache_behavior {
    target_origin_id         = "alb"
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    compress                 = true
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  web_acl_id = var.enable_waf ? aws_wafv2_web_acl.edge[0].arn : null
}

# WAF on the edge: per-IP rate limit (stops install farming and floods before they cost model tokens) + AWS's IP
# reputation list. No body-inspection rule sets: they would block legitimate PDF uploads. CloudFront WAF lives in us-east-1.
resource "aws_wafv2_web_acl" "edge" {
  count    = var.enable_waf ? 1 : 0
  provider = aws.us_east_1
  name     = "${local.prefix}-edge"
  scope    = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 1
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = var.waf_requests_per_5min
        aggregate_key_type = "IP"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-ip-reputation"
    priority = 2
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.prefix}-edge"
    sampled_requests_enabled   = true
  }
}
