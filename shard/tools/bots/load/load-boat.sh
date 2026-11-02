#!/usr/bin/env bash
# Runs the load gate (load.sh) on this checkout's boat VM, which must be 8-vCPU (SHARD_VM_TYPE=large), and copies
# the outputs to tools/bots/load/results/$LABEL/ (default: latest). For each map set in CASES (default: "stone
# default", W1.6's stone worlds then the 18 default maps), 1,000 bots in 100 worlds, then the same with transfers.
# Extra env (HEAP, SHARD_GC, BOTS...) goes through to load.sh.
#   scripts/vm-gradle.sh --delete; SHARD_VM_TYPE=large LABEL=w2.4 tools/bots/load/load-boat.sh
set -euo pipefail
here=$(cd "$(dirname "$0")/../../.." && pwd)
boat=${BOAT:-$(command -v boat || { echo "boat not found: install it or set BOAT" >&2; exit 1; })}
export SHARD_VM_TYPE=${SHARD_VM_TYPE:-large}
mkdir -p "$here/build"
pass=""; for v in HEAP SHARD_GC BOTS WORLDS DUR WARMUP; do [ -n "${!v:-}" ] && pass+="$v=${!v} "; done
runs="rm -rf ~/load-out && ./gradlew --console=plain -q :server:installDist :tools:bots:installDist && ./gradlew -q --stop"
for maps in ${CASES:-stone default}; do
  runs+=" && SKIP_BUILD=1 MAPS=$maps ${pass}tools/bots/load/load.sh && SKIP_BUILD=1 MAPS=$maps TRANSFER=30 ${pass}tools/bots/load/load.sh"
done
"$here/scripts/vm-gradle.sh" --shell "$runs" | tee "$here/build/load-summary.txt"
id=$(cat "$here/.vm-id")
out="$here/tools/bots/load/results/${LABEL:-latest}"
rm -rf "$out"; mkdir -p "$out"
"$boat" exec "$id" --no-update -- bash -lc 'cd ~/load-out && tar -czf ~/load-out.tgz *.txt' > /dev/null
"$boat" scp "$id:/home/user/load-out.tgz" "$out/load-out.tgz" --no-update > /dev/null
tar -xzf "$out/load-out.tgz" -C "$out" && rm "$out/load-out.tgz"
mv "$here/build/load-summary.txt" "$out/summary.txt"
echo "results in $out"
