#!/usr/bin/env bash
# Build the server image and push it to the registry created by Terraform. Usage: infra/scripts/push-image.sh <region>
# Prints the tag to pass as image_tag. Requires: docker, aws CLI with credentials for the TARGET (personal/team) account.
set -euo pipefail
region="${1:?usage: push-image.sh <aws-region>}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
repo="$(terraform -chdir="$root/infra/terraform/envs/demo-aws" output -raw image_repository)"
tag="git-$(git -C "$root" rev-parse --short HEAD)"
docker build -f "$root/server/Dockerfile" -t "$repo:$tag" "$root"
aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "${repo%%/*}"
docker push "$repo:$tag"
echo "$tag"
