#!/usr/bin/env bash
# The load gate on an 8-vCPU boat VM (run it there, from the shard/ directory): the shard server pinned to CPUs 0-3,
# shard-bots pinned to CPUs 4-7, over loopback, as note 24's net-load.sh. The server hosts WORLDS worlds, w00..,
# and joins each bot to the emptiest. MAPS=default cycles the 18 default maps of vanilla's server jar ($VANILLA_JAR);
# MAPS=stone hosts W1.6's 50x50 stone world (StoneMap.java) in each, through the same path. Writes
# <tag>.server.txt (log and console), <tag>.bots.txt and <tag>.gc.txt to $OUT (default ~/load-out) and prints the
# SUMMARY lines: the console's `perf summary` covers WARMUP to DUR seconds after the bots start, and so do the GC
# summary (gc-summary.sh) and the PROFILE line (`perf profile`: the tick threads' CPU by phase, per world tick). The
# server runs 10 s past DUR so the bots leave on their own, not because it closed.
# Env: MAPS (default), BOTS (1000), WORLDS (100), DUR (90 s), WARMUP (20 s), CHAT (30 s), TRANSFER (0 s, 0 = never),
# TAG, HEAP (6g for default maps, 1g for stone, as W1.6; also the initial heap: the launcher pre-touches it),
# SHARD_GC (shenandoah or g1; see bin/shard), SERVER_CPUS (0-3) and BOT_CPUS (4-7), to smoke-test on a smaller VM,
# SERVER_OPTS (more JVM options, for example a profiling recording), SERVER_ARGS (default --quiet; empty logs each
# player). BOTS=0 measures the worlds alone.
set -euo pipefail
cd "$(dirname "$0")/../../.."
BOTS=${BOTS:-1000}; WORLDS=${WORLDS:-100}; DUR=${DUR:-90}; WARMUP=${WARMUP:-20}; CHAT=${CHAT:-30}; TRANSFER=${TRANSFER:-0}
MAPS=${MAPS:-default}; [ "$MAPS" = stone ] && HEAP=${HEAP:-1g}; HEAP=${HEAP:-6g}
TAG=${TAG:-$MAPS-b$BOTS-w$WORLDS-t$TRANSFER}; OUT=${OUT:-$HOME/load-out}; PORT=${PORT:-6567}
JAR=${VANILLA_JAR:-$HOME/vanilla/v160.7/server-release.jar}; SERVER_CPUS=${SERVER_CPUS:-0-3}; BOT_CPUS=${BOT_CPUS:-4-7}
mkdir -p "$OUT"
# stop the daemon after the build: a resident one (after a full check, several GB) lengthened G1 pauses enough to drop ticks
[ -n "${SKIP_BUILD:-}" ] || { ./gradlew --console=plain -q :server:installDist :tools:bots:installDist && ./gradlew -q --stop; }

# the server's config: loopback, no player limit, every bot from 127.0.0.1 under its own uuid, data beside the outputs
conf=$OUT/$TAG.config; rm -rf "$conf"; mkdir -p "$conf/maps"
jar="vanilla-jar: \"$JAR\""
case $MAPS in
  default) maps=(maze fortress labyrinth islands tendrils caldera wasteland shattered fork triad mudFlats moltenLake archipelago debrisField domain veins glacier passage) ;;
  stone) maps=(stone); jar="vanilla-jar: \"\""; java -cp 'server/build/install/shard/lib/*' tools/bots/load/StoneMap.java "$conf/maps/stone.msav" ;;
  *) echo "MAPS is default or stone, not $MAPS"; exit 1 ;;
esac
worlds=""; for i in $(seq 0 $(( WORLDS - 1 ))); do worlds+="\"w$(printf %02d "$i")=${maps[i % ${#maps[@]}]}\", "; done
cat > "$conf/shard.yml" <<YML
net:
  bind: 127.0.0.1
  port: $PORT
  player-limit: 0
ops:
  enabled: false
log:
  format: text
admin:
  uuid-change-limit: 0
host:
  worlds: [${worlds%, }]
maps:
  $jar
  directory: "$conf/maps"
storage:
  database: "$conf/data/shard.db"
  backup-dir: "$conf/data/backups"
persistence:
  directory: "$conf/data/worlds"
YML
log=$OUT/$TAG.server.txt
ready(){ grep -q 'ready: ' "$log" 2>/dev/null; }
# a server that didn't start prints its exception and stops; nothing should wait out the ready timeout then
gone(){ grep -qE '^[a-z.]+[A-Za-z]*(Exception|Error)|Server - stopped' "$log" 2>/dev/null; }

steal(){ awk '/^cpu /{print $9}' /proc/stat; }   # jiffies stolen by the hypervisor, all CPUs
# the console, through stdin: perf mark at WARMUP, a perf line every 10 s, perf summary and profile at DUR, then exit
{
  for _ in $(seq 1 1200); do ready && break; gone && exit 0; sleep 0.5; done
  sleep "$WARMUP"; echo "perf mark"
  for _ in $(seq 1 $(( (DUR - WARMUP) / 10 ))); do sleep 10; echo perf; done
  sleep $(( (DUR - WARMUP) % 10 )); echo "perf summary"; echo "perf profile"
  sleep 10; echo exit
} | JAVA_OPTS="-Xms$HEAP -Xmx$HEAP -Xlog:gc:file=$OUT/$TAG.gc.txt ${SERVER_OPTS:-}" SHARD_DATA="$conf/data" taskset -c "$SERVER_CPUS" \
  server/build/install/shard/bin/shard --config "$conf" ${SERVER_ARGS---quiet} > "$log" 2>&1 &
S=$!
for _ in $(seq 1 1200); do ready && break; gone && break; sleep 0.5; done
ready || { echo "the server didn't start:"; tail -20 "$log"; exit 1; }
grep 'ready: ' "$log"
ST0=$(steal)
if [ "$BOTS" -gt 0 ]; then
  RAMP=$(( 10000 / BOTS )); [ "$RAMP" -lt 1 ] && RAMP=1
  JAVA_OPTS='-Xms2g -Xmx2g' taskset -c "$BOT_CPUS" tools/bots/build/install/shard-bots/bin/shard-bots --port "$PORT" --bots "$BOTS" \
    --duration "$DUR" --ramp-ms "$RAMP" --report 10 --chat-s "$CHAT" --transfer-s "$TRANSFER" --worlds "$WORLDS" \
    > "$OUT/$TAG.bots.txt" 2>&1 || true
fi
wait $S || true
ST1=$(steal)
echo "== $TAG on $(nproc) vCPUs, $HEAP heap (hypervisor steal during run: $(( (ST1 - ST0) / 100 )) cpu-seconds)"
grep SUMMARY "$log" || tail -5 "$log"
grep PROFILE "$log" || true
mark=$(sed -n 's/.*perf mark at t=\([0-9]*\)s.*/\1/p' "$log" | head -1); secs=$(sed -n 's/.*SUMMARY seconds=\([0-9]*\).*/\1/p' "$log" | head -1)
[ -n "$mark" ] && [ -n "$secs" ] && tools/bots/load/gc-summary.sh "$OUT/$TAG.gc.txt" "$mark" $(( mark + secs ))
[ "$BOTS" -gt 0 ] || exit 0
grep 'BOT SUMMARY' "$OUT/$TAG.bots.txt" || tail -5 "$OUT/$TAG.bots.txt"
grep 'bots t=' "$OUT/$TAG.bots.txt" | tail -1
grep 'close:' "$OUT/$TAG.bots.txt" | head -5 || true
