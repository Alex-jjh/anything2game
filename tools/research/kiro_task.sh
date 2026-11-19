#!/usr/bin/env bash
# Run one web-search research prompt through kiro-cli headless; answer -> <out>.md, log -> <out>.log. Research tooling only.
# Usage: kiro_task.sh <prompt-file> <out-path-without-ext> [model]
set -u
prompt="$1"; out="$2"; model="${3:-claude-sonnet-5.5}"
mkdir -p "$(dirname "$out")"
cd /tmp && timeout 1800 kiro-cli chat --no-interactive --model "$model" --trust-tools=web_search,web_fetch "$(cat "$prompt")" > "$out.md" 2> "$out.log"
echo "rc=$?" >> "$out.log"
