#!/usr/bin/env bash
# Restart the in-JVM node to kill a runaway (CPU-hang) parse and reclaim the core.
# (For an OOM the container has already exited -- re-run `docker compose --profile jvm up` instead.)
cd "$(dirname "$0")"
name="$(basename "$PWD")-es-jvm-1"
echo "restarting $name ..."
docker restart "$name"
