#!/usr/bin/env bash
# A/B of the five-world allocation bench on one 8-vCPU boat VM: a baseline checkout against this one. Run it on the VM
# from this shard/ directory, with the baseline's shard/ directory at $BASE (default ~/base/shard); bench-boat.sh sets
# both up from the dev box. Builds both servers (the bots come from this checkout), stops the Gradle daemons, then runs
# ROUNDS rounds (default 2), the baseline first in odd rounds and this checkout first in even ones (base, mine, mine,
# base), so a drifting host weighs on both sides. Writes $OUT/{base,mine}-r<round>/ (default OUT ~/bench-out) and
# $OUT/summary.txt: every run's RUN line and top sites. Extra env goes through to bench.py.
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
BASE=${BASE:-$HOME/base/shard}; OUT=${OUT:-$HOME/bench-out}; ROUNDS=${ROUNDS:-2}
for d in "$BASE" "$here"; do (cd "$d" && ./gradlew --console=plain -q :server:installDist && ./gradlew -q --stop); done
(cd "$here" && ./gradlew --console=plain -q :tools:bots:installDist && ./gradlew -q --stop)
rm -rf "$OUT"; mkdir -p "$OUT"
run(){ # side round
  local dir; [ "$1" = base ] && dir=$BASE || dir=$here
  python3 "$here/tools/bench/bench.py" "$OUT" "$1-r$2" --server "$dir/server/build/install/shard/bin/shard" | grep "^RUN"
}
for round in $(seq 1 "$ROUNDS"); do
  if [ $(( round % 2 )) = 1 ]; then run base "$round"; run mine "$round"; else run mine "$round"; run base "$round"; fi
done
cat "$OUT"/*/analysis.txt > "$OUT/summary.txt"
