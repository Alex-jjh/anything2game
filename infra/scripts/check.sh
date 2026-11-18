#!/usr/bin/env bash
# Static checks for the infrastructure: formatting, validity, misconfiguration scan. No cloud credentials needed.
set -euo pipefail
cd "$(dirname "$0")/../terraform"
terraform fmt -recursive -check
for env in envs/*/; do
  terraform -chdir="$env" init -backend=false -input=false >/dev/null
  terraform -chdir="$env" validate
done
trivy config --exit-code 1 --severity HIGH,CRITICAL,MEDIUM modules
