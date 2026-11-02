#!/usr/bin/env bash
# The Shard server from a pushed install, for rig runs:
#   rig/run.sh --push server/build/install/shard --server '"$RIG"/vm/shard-server.sh [--aot] [--join POLICY] WORLD...' ...
# Writes shard.yml in the run's server/ directory: loopback on $PORT, the default maps of $VANILLA's server jar,
# host.worlds from the arguments (entries as host.worlds takes them: maze, or fortress pvp) and host.join from
# --join (default first). Data, JFR recordings and temporary files go under server/data/. Its stdin is the console.
# To tell a stall of the JVM from one of the VM, it logs safepoints to server/safepoint.log and the VM's vmstat (steal,
# run queue) to server/vmstat.txt, once a second (its first line averages since boot). --aot first trains the build's
# AOT cache on this config, as a deploy does (`bin/shard --aot-train`, about a minute; its output in server/aot.txt),
# so the server starts from it; scenarios that use it wait for the port long enough.
set -euo pipefail
aot=0
if [ "${1:-}" = --aot ]; then aot=1; shift; fi
join=first
if [ "${1:-}" = --join ]; then join=$2; shift 2; fi
[ $# -gt 0 ] || { echo "shard-server.sh: name at least one world" >&2; exit 2; }
worlds=$(printf '"%s", ' "$@")
cat > shard.yml <<YML
server:
  name: "Shard (rig)"
net:
  bind: "127.0.0.1"
  port: ${PORT:-6567}
ops:
  enabled: false
log:
  format: text
host:
  worlds: [${worlds%, }]
  join: $join
maps:
  vanilla-jar: "$VANILLA/server-release.jar"
YML
[ -n "${JAVA:-}" ] && export JAVA_HOME=$(dirname "$(dirname "$JAVA")")
if [ "$aot" = 1 ]; then
  # the run's JVM options, without the scenario's debug ones (a delayed stream would only slow the bots)
  env -u SHARD_OPTS SHARD_DATA=data JAVA_OPTS="${JAVA_OPTS:-} -Xlog:safepoint=info:file=aot-safepoint.log:uptime,time" \
    "$PUSH/bin/shard" --aot-train --config . > aot.txt 2>&1 \
    || { echo "shard-server.sh: AOT training failed, see aot.txt" >&2; exit 1; }
  ls -l data/aot/*.aot >> aot.txt
fi
vmstat -t -w 1 > vmstat.txt 2>&1 &
exec env SHARD_DATA=data JAVA_OPTS="${JAVA_OPTS:-} -Xlog:safepoint=info:file=safepoint.log:uptime,time" "$PUSH/bin/shard" --config .
