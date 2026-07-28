#!/usr/bin/env bash
# Create the `attach` ingest pipeline on the running node (:9200). Run once after each
# `docker compose --profile <jvm|server> up` (each profile is a fresh single-node cluster).
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-9200}"
PIPELINE='{"processors":[{"attachment":{"field":"data","remove_binary":true}}]}'
printf 'pipeline "attach" on :%s -> ' "$PORT"
curl -s -XPUT "localhost:${PORT}/_ingest/pipeline/attach" -H 'Content-Type: application/json' -d "$PIPELINE"; echo
