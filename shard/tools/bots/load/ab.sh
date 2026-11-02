#!/usr/bin/env bash
# A/B of the load gate on one 8-vCPU boat VM: a baseline checkout against this one, alternating cases, with the
# Gradle daemons stopped before measuring. Run it on the VM from this shard/ directory, with the baseline's shard/
# directory at $BASE (default ~/base/shard), for example after
#   git archive <base-sha> shard | tar -x -C <dir>; boat push <vm> <dir> --to /home/user/base
# Each of ROUNDS rounds (default 1) runs every map set in CASES (default: default) without and with transfers, the
# baseline first in odd rounds and this checkout first in even ones, so a drifting host weighs on both sides. Writes
# base-<maps>-t<n>-r<round>.* and mine-<maps>-t<n>-r<round>.* to $OUT (default ~/ab), as load.sh does; read the
# hypervisor steal in each == line and compare quiet pairs.
set -euo pipefail
here=$(cd "$(dirname "$0")/../../.." && pwd)
BASE=${BASE:-$HOME/base/shard}; OUT=${OUT:-$HOME/ab}; ROUNDS=${ROUNDS:-1}
for d in "$BASE" "$here"; do (cd "$d" && ./gradlew --console=plain -q :server:installDist :tools:bots:installDist && ./gradlew -q --stop); done
rm -rf "$OUT"; mkdir -p "$OUT"
run(){ # side maps transfer round
  local dir; [ "$1" = base ] && dir=$BASE || dir=$here
  (cd "$dir" && SKIP_BUILD=1 MAPS=$2 TRANSFER=$3 OUT="$OUT" TAG=$1-$2-t$3-r$4 tools/bots/load/load.sh)
}
for round in $(seq 1 "$ROUNDS"); do
  for maps in ${CASES:-default}; do
    for t in 0 30; do
      if [ $(( round % 2 )) = 1 ]; then run base "$maps" "$t" "$round"; run mine "$maps" "$t" "$round"
      else run mine "$maps" "$t" "$round"; run base "$maps" "$t" "$round"; fi
    done
  done
done
