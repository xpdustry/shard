#!/usr/bin/env bash
# The live replay gate (RP2), on a boat VM: a bot match of FIRST + SECOND minutes on replay-gate.msav (GateMap.java)
# with replay.record and persistence.resume, the server stopped with SIGTERM between the two and started again, then
# `shard replay verify` (every segment made again from the bundle, byte for byte, and the world's hash at every
# checkpoint) and `shard replay export` for the viewer. Run it from ~/shard on the VM after building:
#
#   scripts/vm-gradle.sh :server:installDist :tools:bots:installDist :tools:oracle:jar
#   scripts/vm-gradle.sh --shell tools/replay/gate/live-gate.sh [FIRST=6] [SECOND=5] [BOTS=6]
#
# Everything goes to ~/gate: map.txt, server/ (shard.yml, server-1.log, server-2.log, data/replays), bots-1.log,
# bots-2.log, list.txt, verify.txt, and view.sview plus view/ (the stream, split). The server binds 127.0.0.1.
set -euo pipefail
first=${1:-6} second=${2:-5} bots=${3:-6}
here=$(pwd)
out=$HOME/gate
rm -rf "$out"
mkdir -p "$out/maps" "$out/server"
jar=$HOME/.cache/shard/vanilla/v160.7/server-release.jar
shard=$here/server/build/install/shard/bin/shard
botsBin=$here/tools/bots/build/install/shard-bots/bin/shard-bots

java -Dcom.xpdustry.shard.oracle.dataDir="$out/oracle" -cp "$here/tools/oracle/build/libs/oracle.jar:$jar" \
  "$here/tools/replay/gate/GateMap.java" "$out/maps/replay-gate.msav" > "$out/map.txt" 2>&1 || { cat "$out/map.txt"; exit 1; }
head -1 "$out/map.txt"

cat > "$out/server/shard.yml" <<YML
server:
  name: "Shard (replay gate)"
net:
  bind: "127.0.0.1"
  port: 6567
ops:
  enabled: false
log:
  format: text
host:
  worlds: ["gate=replay-gate survival"]
maps:
  directory: "$out/maps"
game:
  simulate-when-empty: true
persistence:
  resume: true
  hibernate-after: 0s
replay:
  record: true
YML

pid=
start(){
  (cd "$out/server" && SHARD_DATA=data exec "$shard" --config . > "$out/server/server-$1.log" 2>&1 < /dev/null) &
  pid=$!
  for _ in $(seq 120); do
    # it listens before its worlds are hosted: wait for "ready"
    grep -q ' - ready: ' "$out/server/server-$1.log" 2>/dev/null && return 0
    kill -0 "$pid" 2>/dev/null || { cat "$out/server/server-$1.log"; echo "gate: the server died" >&2; exit 1; }
    sleep 1
  done
  echo "gate: the server didn't listen" >&2
  exit 1
}

stop(){
  kill -TERM "$pid"
  for _ in $(seq 120); do kill -0 "$pid" 2>/dev/null || return 0; sleep 1; done
  echo "gate: the server didn't stop" >&2
  exit 1
}

play(){
  "$botsBin" --bots "$bots" --ramp-ms 1500 --duration $(( $1 * 60 )) --report 60 --chat-s 20 --churn-s 120 --walk > "$out/bots-$2.log" 2>&1 || true
  grep -E '^BOT (SUMMARY|CHURN)' "$out/bots-$2.log" || tail -5 "$out/bots-$2.log"
}

echo "gate: part 1, $first min"
start 1
play "$first" 1
stop
echo "gate: part 2 after a restart, $second min"
start 2
play "$second" 2
stop
grep -hE 'replay|resum|match' "$out/server/server-1.log" "$out/server/server-2.log" | grep -v DEBUG | head -20 || true

"$shard" replay list --config "$out/server" | tee "$out/list.txt"
match=$(basename "$(ls -d "$out"/server/data/replays/bundles/*/*/ | head -1)")
echo "gate: match $match"
status=0
"$shard" replay verify "$match" --config "$out/server" > "$out/verify.txt" 2>&1 || status=$?
tail -4 "$out/verify.txt"
"$shard" replay export "$match" --config "$out/server" --out "$out/view.sview" --split "$out/view" | tail -2
exit $status
