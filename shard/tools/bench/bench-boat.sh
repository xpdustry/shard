#!/usr/bin/env bash
# Runs ab.sh on this checkout's boat VM (scripts/vm-gradle.sh's; it must be 8-vCPU: SHARD_VM_TYPE=large on its first
# use) against BASE_REF (a commit; default main), and copies the results to tools/bench/results/$LABEL/
# (default latest): summary.txt and each run's analysis.txt and console summary. The JFR recordings stay on the VM,
# in ~/bench-out. Extra env (ROUNDS, WARMUP, MEASURE...) goes through to ab.sh and bench.py.
#   SHARD_VM_TYPE=large LABEL=w4.6-tiles tools/bench/bench-boat.sh 9ae67530
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
boat=${BOAT:-$(command -v boat || { echo "boat not found: install it or set BOAT" >&2; exit 1; })}
ref=${1:-main}
export SHARD_VM_TYPE=${SHARD_VM_TYPE:-large}
pass=""; for v in ROUNDS WARMUP MEASURE BOTS WORLDS HEAP_MIN HEAP_MAX SHARD_GC; do [ -n "${!v:-}" ] && pass+="$v=${!v} "; done
# the baseline's sources, as the VM's ~/base/shard; staged outside the checkout, since boat push skips git-ignored paths
stage="${BENCH_STAGE:-$HOME/.cache/shard/scratch/bench-base-$$}"; rm -rf "$stage"; mkdir -p "$stage"
trap 'rm -rf "$stage"' EXIT
git -C "$here/.." archive "$ref" shard | tar -x -C "$stage"
"$here/scripts/vm-gradle.sh" --shell 'true' > /dev/null   # creates or resumes the VM and syncs this checkout
id=$(cat "$here/.vm-id")
"$boat" exec "$id" --no-update -- bash -lc 'rm -rf ~/base' > /dev/null
"$boat" push "$id" "$stage" --to /home/user/base --no-update --json > /dev/null
"$here/scripts/vm-gradle.sh" --shell "${pass}tools/bench/ab.sh"
out="$here/tools/bench/results/${LABEL:-latest}"; rm -rf "$out"; mkdir -p "$out"
"$boat" exec "$id" --no-update -- bash -lc 'cd ~/bench-out && tar -czf ~/bench-out.tgz summary.txt */analysis.txt */server.log' > /dev/null
"$boat" scp "$id:/home/user/bench-out.tgz" "$out/bench-out.tgz" --no-update > /dev/null
tar -xzf "$out/bench-out.tgz" -C "$out" && rm "$out/bench-out.tgz"
# keep the console's perf lines, not every join and chat line
for log in "$out"/*/server.log; do grep -E '^(perf |SUMMARY|PROFILE|Status|  w[0-9])' "$log" > "${log%server.log}console.txt" || true; rm "$log"; done
echo "results in $out"
