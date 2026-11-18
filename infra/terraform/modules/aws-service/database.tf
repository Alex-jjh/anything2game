# Managed PostgreSQL in private subnets, never publicly accessible. The master password is generated and rotated by
# RDS in Secrets Manager (it never appears in Terraform state or code).

resource "aws_db_subnet_group" "main" {
  name       = local.prefix
  subnet_ids = aws_subnet.private[*].id
}

#trivy:ignore:AVD-AWS-0176 -- the server authenticates with the RDS-managed, rotated password from Secrets Manager; no IAM DB auth client in the server (ADR-012: no cloud SDK).
resource "aws_db_instance" "main" {
  identifier                   = local.prefix
  engine                       = "postgres"
  engine_version               = var.db_engine_version
  instance_class               = var.db_tier
  allocated_storage            = var.db_storage_gb
  storage_type                 = "gp3"
  storage_encrypted            = true
  db_name                      = "a2g"
  username                     = "a2g"
  manage_master_user_password  = true
  db_subnet_group_name         = aws_db_subnet_group.main.name
  vpc_security_group_ids       = [aws_security_group.db.id]
  publicly_accessible          = false
  multi_az                     = false
  backup_retention_period      = 7
  auto_minor_version_upgrade   = true
  deletion_protection          = var.deletion_protection
  skip_final_snapshot          = !var.deletion_protection
  final_snapshot_identifier    = var.deletion_protection ? "${local.prefix}-final" : null
  copy_tags_to_snapshot        = true
  performance_insights_enabled = false
}
