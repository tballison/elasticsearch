#!/usr/bin/env bash
# Live CPU/mem for whatever containers of this demo are running (either profile). Own terminal.
cd "$(dirname "$0")"
ids=$(docker ps --filter "label=com.docker.compose.project=$(basename "$PWD")" -q)
exec docker stats $ids
