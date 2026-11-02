#!/usr/bin/env bash
# W2.4h's AOT cache measurement through bin/shard, on an 8-vCPU boat VM from the shard/ directory (as load.sh). Under
# each GC, trains a cache with `bin/shard --aot-train` (40 bots join, chat, move between the 18 worlds, one is kicked
# from the console). Then it starts the server REPS times per GC and cache (none, trained under the same GC, trained
# under the other) and measures the time to "ready:" and the longest tick of the first join and of the first leave (one
# bot, from the console's perf lines), and, in one more run, where classes came from (-Xlog:class+load). Last, the
# launcher's own path: --aot-train into SHARD_DATA's default cache, then a plain start finds it.
# Env: JAVA_HOME (/opt/jdk27), REPS (5), OUT (~/aot-out), HEAP (2g).
set -uo pipefail
cd "$(dirname "$0")/../../.."
export JAVA_HOME=${JAVA_HOME:-/opt/jdk27}
REPS=${REPS:-5}; OUT=${OUT:-$HOME/aot-out}; HEAP=${HEAP:-2g}; PORT=6567
JAR=$HOME/vanilla/v160.7/server-release.jar
rm -rf "$OUT"; mkdir -p "$OUT"
[ -n "${SKIP_BUILD:-}" ] || { ./gradlew --console=plain -q :server:installDist :tools:bots:installDist && ./gradlew -q --stop; }
maps=(maze fortress labyrinth islands tendrils caldera wasteland shattered fork triad mudFlats moltenLake archipelago debrisField domain veins glacier passage)
worlds=""; for i in "${!maps[@]}"; do worlds+="\"w$(printf %02d "$i")=${maps[i]}\", "; done
conf=$OUT/conf; mkdir -p "$conf/maps"
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
  kick-cooldown: 0s
host:
  worlds: [${worlds%, }]
maps:
  vanilla-jar: "$JAR"
  directory: "$conf/maps"
storage:
  database: "$conf/data/shard.db"
  backup-dir: "$conf/data/backups"
persistence:
  directory: "$conf/data/worlds"
YML
bots=tools/bots/build/install/shard-bots/bin/shard-bots

# serve <log> <gc> <SHARD_AOT> <SHARD_AOT_CACHE or ""> <JVM opts> <console script> [keep]: runs the server with the
# script on its console, from a fresh data directory unless keep
serve() {
  local log=$1 gc=$2 aot=$3 cache=$4 opts=$5 script=$6
  [ -n "${7:-}" ] || rm -rf "$conf/data"
  { for _ in $(seq 1 3000); do grep -q 'ready: ' "$log" 2>/dev/null && break; sleep 0.01; done; $script; echo exit; } \
    | env JAVA_OPTS="-Xms$HEAP -Xmx$HEAP $opts" SHARD_GC=$gc SHARD_AOT=$aot ${cache:+SHARD_AOT_CACHE=$cache} SHARD_DATA="$conf/data" \
      taskset -c 0-3 server/build/install/shard/bin/shard --config "$conf" > "$log" 2>&1
}

# train <out> <gc> <SHARD_AOT_CACHE or "">: bin/shard --aot-train from a fresh data directory; its output in <out>.txt,
# the server's log in <out>.log
train() {
  rm -rf "$conf/data"
  env JAVA_OPTS="-Xms$HEAP -Xmx$HEAP" SHARD_GC=$2 ${3:+SHARD_AOT_CACHE=$3} SHARD_DATA="$conf/data" \
    server/build/install/shard/bin/shard --aot-train --config "$conf" > "$1.txt" 2>&1
  cp "$conf/data/aot/train.log" "$1.log"
}

# one measured run: perf to drain, one bot joins and stays 6 s, perf after its join and after its leave
measure_script() {
  echo perf; sleep 1
  JAVA_OPTS='-Xms256m -Xmx256m' taskset -c 4-7 $bots --port $PORT --bots 1 --duration 6 --report 60 > /dev/null 2>&1 &
  sleep 3; echo perf; wait; sleep 2; echo perf
}

max_tick() { # <log> <n>: the max of the n-th perf line's tick histogram, in ms
  grep -o 'tick\[[^]]*\]' "$1" | sed -n "${2}p" | sed -n 's/.*max=\([0-9]*\)us.*/\1/p' | awk '{printf "%.1f", $1 / 1000}'
}

classes() { # <class+load log>
  awk '/source: shared objects file/{s++} /source: jrt:/{j++} /source: (file|jar):/{f++} END{printf "classes %d: cache %d, jrt %d, jar %d", NR, s, j, f}' "$1"
}

stamp() { # <log> <pattern>: the first matching line's log timestamp, in epoch ms
  python3 - "$1" "$2" <<'PY'
import sys, datetime
for line in open(sys.argv[1], errors="replace"):
    if sys.argv[2] in line:
        print(round(datetime.datetime.strptime(line[:23], "%Y-%m-%dT%H:%M:%S.%f").replace(tzinfo=datetime.timezone.utc).timestamp() * 1000)); break
PY
}

echo "== $("$JAVA_HOME/bin/java" -version 2>&1 | head -1); $(nproc) vCPUs, heap $HEAP"
for gc in shenandoah g1; do
  cache=$OUT/shard-$gc.aot
  t0=$(date +%s%3N)
  train "$OUT/train-$gc" $gc "$cache"
  # the cache is written after "stopped", while the process exits
  end=$(date +%s%3N); stopped=$(stamp "$OUT/train-$gc.log" ' stopped')
  echo "trained under $gc in $((end - t0)) ms, the cache written in $((end - stopped)) ms after stopping: $(du -h "$cache" 2>/dev/null | cut -f1), $(grep -c 'kicked' "$OUT/train-$gc.log") kicks, $(find "$conf/data/jfr" -name '*.jfr' | wc -l) JFR files; $(grep -h '^shard: trained\|^shard: no AOT' "$OUT/train-$gc.txt" | cut -c1-200)"
done
for gc in shenandoah g1; do
  other=g1; [ $gc = g1 ] && other=shenandoah
  for case in none $gc $other; do
    aot=auto; [ $case = none ] && aot=off
    cache=""; [ $case != none ] && cache=$OUT/shard-$case.aot
    ready=(); join=(); leave=()
    for r in $(seq 1 "$REPS"); do
      log=$OUT/run-$gc-$case-$r.log
      t0=$(date +%s%3N)
      serve "$log" $gc $aot "$cache" "" measure_script
      ready+=("$(( $(stamp "$log" 'ready: ') - t0 ))")
      join+=("$(max_tick "$log" 2)"); leave+=("$(max_tick "$log" 3)")
    done
    serve "$OUT/cl-$gc-$case.log" $gc $aot "$cache" "-Xlog:class+load=info:file=$OUT/cl-$gc-$case.txt" measure_script
    warn=$(grep -h -m1 -iE '\[(warning|error)\]\[(aot|cds)' "$OUT"/run-$gc-$case-1.log "$OUT/cl-$gc-$case.log" 2>/dev/null | head -1 | cut -c1-160)
    echo "$gc, cache $case: ready ms [${ready[*]}] first join max tick ms [${join[*]}] first leave [${leave[*]}] $(classes "$OUT/cl-$gc-$case.txt")${warn:+ | $warn}"
  done
done

# the launcher's default path: --aot-train into SHARD_DATA/aot, then a plain start finds it
train "$OUT/default-train" shenandoah ""
serve "$OUT/default-auto.log" shenandoah auto "" "-Xlog:class+load=info:file=$OUT/cl-default.txt" measure_script keep
echo "default path: $(ls "$conf/data/aot" | tr '\n' ' '); auto: $(classes "$OUT/cl-default.txt"); $(grep -h '^shard:' "$OUT"/default-*.txt "$OUT"/default-auto.log | tr '\n' ' ')"
