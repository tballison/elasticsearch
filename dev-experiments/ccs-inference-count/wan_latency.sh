#!/usr/bin/env bash
# Injects artificial WAN latency on the inter-cluster link ONLY, using tc/netem
# with dst-IP filters. Intra-remote-cluster traffic (es-remote-1 <-> es-remote-2)
# and calls to mock-inference are NOT delayed.
#
# Requires the static IPs assigned in docker-compose.yml:
#   es-local 172.28.0.10, es-remote-1 172.28.0.21, es-remote-2 172.28.0.22
#
# tc runs in an ephemeral nicolaka/netshoot container that joins the target
# container's network namespace with NET_ADMIN (the ES image has no tc binary).
#
# Usage:
#   ./wan_latency.sh set 50     # 50ms one-way each direction => ~100ms RTT
#   ./wan_latency.sh clear      # remove all delay
#   ./wan_latency.sh show       # show qdiscs/filters on all three nodes
#   ./wan_latency.sh ping       # measure actual RTT es-local -> es-remote-1
set -euo pipefail

LOCAL_IP=172.28.0.10
REMOTE1_IP=172.28.0.21
REMOTE2_IP=172.28.0.22
NETSHOOT=nicolaka/netshoot

tc_in() {
    local container=$1; shift
    docker run --rm --network "container:$container" --cap-add NET_ADMIN "$NETSHOOT" sh -c "$*"
}

# Delay egress on eth0 for packets whose dst matches the given IPs.
# prio qdisc with priomap all-zeros: default traffic -> band 1 (untouched),
# u32-filtered dst IPs -> band 4 (netem delay).
apply_delay() {
    local container=$1 ms=$2; shift 2
    local cmds="tc qdisc del dev eth0 root 2>/dev/null || true"
    cmds+=" && tc qdisc add dev eth0 root handle 1: prio bands 4 priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
    cmds+=" && tc qdisc add dev eth0 parent 1:4 handle 40: netem delay ${ms}ms"
    for ip in "$@"; do
        cmds+=" && tc filter add dev eth0 parent 1: protocol ip prio 1 u32 match ip dst ${ip}/32 flowid 1:4"
    done
    tc_in "$container" "$cmds"
    echo "  $container: ${ms}ms egress delay -> $*"
}

case "${1:-}" in
    set)
        MS="${2:?usage: $0 set <one-way-ms>}"
        echo "Applying ${MS}ms one-way delay on the inter-cluster link (RTT ~$((MS * 2))ms)..."
        apply_delay es-local    "$MS" "$REMOTE1_IP" "$REMOTE2_IP"
        apply_delay es-remote-1 "$MS" "$LOCAL_IP"
        apply_delay es-remote-2 "$MS" "$LOCAL_IP"
        echo "Done. Verify with: $0 ping"
        ;;
    clear)
        for c in es-local es-remote-1 es-remote-2; do
            tc_in "$c" "tc qdisc del dev eth0 root 2>/dev/null || true"
            echo "  $c: cleared"
        done
        ;;
    show)
        for c in es-local es-remote-1 es-remote-2; do
            echo "=== $c ==="
            tc_in "$c" "tc qdisc show dev eth0; tc filter show dev eth0"
        done
        ;;
    ping)
        echo "--- es-local -> es-remote-1 (should reflect ~2x one-way delay):"
        tc_in es-local "ping -c 3 $REMOTE1_IP"
        echo "--- es-remote-1 -> es-remote-2 (should be sub-millisecond, NOT delayed):"
        tc_in es-remote-1 "ping -c 3 $REMOTE2_IP"
        ;;
    *)
        echo "usage: $0 set <one-way-ms> | clear | show | ping" >&2
        exit 1
        ;;
esac
