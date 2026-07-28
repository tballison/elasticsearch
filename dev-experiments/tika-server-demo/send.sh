#!/usr/bin/env bash
# Send a mock document through the `attach` pipeline on whichever node is up (both bind :9200).
#   ./send.sh <doc.xml>
#   CLIENT_TIMEOUT=8 ./send.sh cpu.xml     # override how long the client waits before giving up
#   PORT=9201 ./send.sh cpu.xml            # override the target port if you remapped it
#
# examples:
#   ./send.sh cpu.xml       # CPU busy-loop
#   ./send.sh oom.xml       # OutOfMemoryError
#   ./send.sh exit.xml      # System.exit
#   ./send.sh normal.xml    # well-behaved (sanity / recovery)
set -uo pipefail
cd "$(dirname "$0")"

DOC="${1:?usage: ./send.sh <doc.xml>}"
PORT="${PORT:-9200}"
CLIENT_TIMEOUT="${CLIENT_TIMEOUT:-60}"
[ -f "$DOC" ] || { echo "no such doc: $DOC" >&2; exit 2; }

B64=$(base64 < "$DOC" | tr -d '\n')
echo ">>> sending '$DOC' -> localhost:$PORT ; client gives up after ${CLIENT_TIMEOUT}s"
curl -s --max-time "$CLIENT_TIMEOUT" -XPUT "localhost:${PORT}/demo/_doc/1?pipeline=attach" \
  -H 'Content-Type: application/json' -d "{\"data\":\"${B64}\"}" \
  -w '\n>>> HTTP=%{http_code} elapsed=%{time_total}s\n' \
  || echo ">>> client gave up (curl timeout) -- but server-side work may still be running (watch.sh)"
