#!/usr/bin/env bash
# W2.4's L5 gate on boat, from the dev box: builds the shard install, runs shard-two-clients.rig, shard-maps.rig and
# shard-loads.rig against it, and copies a summary of each run to build/rig-evidence/$LABEL/<scenario>/ (RIG_EVIDENCE sets the directory;
# default label: shard): the verdict, the step log, the server's warnings and errors, the VM's steal and the JVM's safepoints, and
# each client's last screenshot. The whole run stays in scratch. Each server first trains the build's AOT cache, as a
# deploy does (shard-server.sh --aot): without it, a world's first ticks, a first join and a first transfer run cold.
# Each run gets a large (8-vCPU) VM, with the server on CPUs 0-3 and the clients on 4-7 (two clients on 2 CPUs left
# one's app thread silent for 10 s). SCENARIOS picks some of them. Extra arguments go to rig/run.sh (for example
# --vm "$(cat .vm-id)" to use your vm-gradle VM, which must be large too). It never starts a client here.
#   rig/shard-l5.sh --vm "$(cat .vm-id)"
#   SCENARIOS=shard-loads rig/shard-l5.sh
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
label=${LABEL:-shard}
maps="maze fortress labyrinth islands tendrils caldera wasteland shattered fork triad mudFlats moltenLake archipelago debrisField domain veins glacier passage"
cd "$here"
./gradlew --console=plain -q :server:installDist
# boat push skips ignored paths (build/), so push a copy from scratch
push=$HOME/.cache/shard/scratch/rig/push-shard
rm -rf "$push"; mkdir -p "$push"; cp -r server/build/install/shard "$push/shard"
rc=0
# 256-colour PNGs, a third of the size and alike to the eye, if Pillow is there
shrink(){ # dir
  python3 - "$1" <<'PY' 2>/dev/null || echo "shard-l5: shots left as they are (no Pillow)"
import pathlib, sys
from PIL import Image
for p in pathlib.Path(sys.argv[1]).rglob("*.png"):
    Image.open(p).convert("RGB").quantize(256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(p, optimize=True)
PY
}
run(){ # scenario clients server-env server-args
  case " ${SCENARIOS:-shard-two-clients shard-maps shard-loads} " in *" $1 "*) ;; *) return 0 ;; esac
  local out=$HOME/.cache/shard/scratch/rig/runs/$(date +%Y%m%d-%H%M%S)-$1
  rig/run.sh --push "$push/shard" --server "$3\"\$RIG\"/vm/shard-server.sh $4" --clients "$2" --script "rig/scenarios/$1.rig" \
    --out "$out" --type large --server-cpus 0-3 --client-cpus 4-7 "${@:5}" || rc=1
  local dest=${RIG_EVIDENCE:-build/rig-evidence}/$label/$1
  rm -rf "$dest"; mkdir -p "$dest"
  cp "$out"/{verdict.json,summary.txt,rig.log} "$dest/" 2>/dev/null || true
  grep -E ' (WARN|ERROR) ' "$out/server/server.log" > "$dest/server-warnings.txt" 2>/dev/null || true
  vm_summary "$out/server" > "$dest/host.txt"
  { echo "AOT training (shard-server.sh --aot):"; tail -n 3 "$out/server/aot.txt" 2>/dev/null || true
    grep -m 1 'AOT' "$out/server/server.log" 2>/dev/null || echo "the server started from the cache (bin/shard printed no AOT hint)"; } >> "$dest/host.txt"
  for c in alpha bravo; do
    local shot
    shot=$(ls "$out/$c/shots/"*.png 2>/dev/null | tail -n 1) || true
    [ -n "$shot" ] && cp "$shot" "$dest/$c-$(basename "$shot")"
  done
  shrink "$dest"
}
vm_summary(){ # server dir: what the VM and the JVM did while it ran
  awk 'NR > 3 && $1 ~ /^[0-9]+$/ { n++; if($1 > r) r = $1; if($17 > st) st = $17; if(min == "" || $15 < min) min = $15; s += $17 }
    END { printf "vmstat, %d s: run queue max %d, idle min %d%%, steal max %d%%, mean %.1f%%\n", n, r, min, st, n ? s / n : 0 }' "$1/vmstat.txt" 2>/dev/null
  awk '/Total: / { n++; t = $0; sub(/.*Total: /, "", t); sub(/ ns.*/, "", t); t += 0; if(t > max){ max = t; line = $0 } }
    END { printf "safepoints: %d, longest %.1f ms\n%s\n", n, max / 1e6, line }' "$1/safepoint.log" 2>/dev/null
  echo "longest lines of the VM's vmstat (by steal):"
  # sed, not head: head's early exit would end the script on SIGPIPE (pipefail, set -e)
  awk 'NR > 3 && $1 ~ /^[0-9]+$/' "$1/vmstat.txt" 2>/dev/null | sort -k17,17nr | sed -n '1,5p'
}
run shard-two-clients 2 "" "--aot maze fortress" "$@"
run shard-maps 2 "" "--aot --join round-robin $maps" "$@"
# maze's streams deflate 8 s late, so a /sync of maze is still deflating when a transfer loads fortress
run shard-loads 1 "SHARD_OPTS=-Dshard.debug.stream-delay=maze=8s " "--aot maze fortress" "$@"
echo "evidence in ${RIG_EVIDENCE:-build/rig-evidence}/$label/ (exit $rc)"
exit $rc
