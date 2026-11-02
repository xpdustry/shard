#!/usr/bin/env bash
# Runs one rig script on this machine, which must be a boat VM (never the shared dev box: it starts vanilla GUI
# clients). Compiles the driver if its sources changed, then runs it in fresh user, network, PID and mount
# namespaces. The only interface is loopback and every process dies with the run.
#
#   rig/vm/run.sh --server vanilla --clients 2 --script rig/scenarios/vanilla-groundzero.rig --out ~/rig-runs/x
#
# --server takes a shell command, run in <out>/server/ with $PORT, $VANILLA (the jar directory), $JAVA, $RIG (this
# rig/ directory), $RIG_OUT and $PUSH (from --push) set. `vanilla [map...]` runs the vanilla server-release.jar
# through vm/vanilla-server.sh. Other flags go to Rig.java.
set -euo pipefail
rig=$(cd "$(dirname "$0")/.." && pwd)
JAR=${MINDUSTRY_JAR:-}
for c in "$HOME/vanilla/v160.7/Mindustry.jar" "$HOME/.cache/shard/vanilla/v160.7/Mindustry.jar"; do
  [ -z "$JAR" ] && [ -f "$c" ] && JAR=$c
done
[ -f "${JAR:-}" ] || { echo "rig: no Mindustry.jar (set MINDUSTRY_JAR)" >&2; exit 2; }
JAVA=${JAVA:-$(command -v java)}
RIG=$rig
export JAVA RIG

build=${RIG_BUILD:-$HOME/.cache/shard/rig-build}
hash=$(find "$rig/src" -name '*.java' | sort | xargs cat | sha1sum | cut -c1-12)
if [ ! -f "$build/$hash.ok" ]; then
  rm -rf "$build" && mkdir -p "$build"
  javac --release 25 -cp "$JAR" -d "$build/classes" $(find "$rig/src" -name '*.java')
  touch "$build/$hash.ok"
fi

args=()
while [ $# -gt 0 ]; do
  case $1 in
    --server)
      cmd=$2
      # `vanilla [map...]`: the vanilla server, with sector maps from the jar installed (vm/vanilla-server.sh).
      [[ $cmd == vanilla || $cmd == "vanilla "* ]] && cmd="exec \"\$RIG/vm/vanilla-server.sh\"${cmd#vanilla}"
      args+=(--server "$cmd"); shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done

# The script's own `timeout` ends the run cleanly; this is the backstop if the driver itself hangs.
exec timeout --kill-after=15 "${RIG_HARD_TIMEOUT:-1800}" \
  unshare --user --map-root-user --net --pid --fork --mount-proc --kill-child \
  bash -c 'ip link set lo up && exec "$@"' rig \
  "$JAVA" -cp "$build/classes" com.xpdustry.shard.rig.Rig --jar "$JAR" --java "$JAVA" "${args[@]}"
