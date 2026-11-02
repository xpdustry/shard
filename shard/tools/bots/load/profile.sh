#!/usr/bin/env bash
# The load gate (load.sh) with a profiling JFR recording, on the VM from the shard/ directory: CPU-time samples
# (jdk.CPUTimeSample, one per PERIOD of each thread's CPU), allocation samples and per-thread allocation, and lock
# waits from 1 ms, stacks 192 deep. After the run, JfrProfile.java reads the recording over the gate's window (perf
# mark to perf summary) into <tag>.profile.txt, with folded stacks for flame graphs beside it. Env as load.sh's (MAPS, TRANSFER, OUT...), plus
# PERIOD (1ms). TAG defaults to prof-<maps>-t<transfer>.
#   SKIP_BUILD=1 MAPS=stone tools/bots/load/profile.sh
set -euo pipefail
cd "$(dirname "$0")/../../.."
MAPS=${MAPS:-default}; TRANSFER=${TRANSFER:-0}; OUT=${OUT:-$HOME/load-out}
TAG=${TAG:-prof-$MAPS-t$TRANSFER}
mkdir -p "$OUT"
jfr="-XX:FlightRecorderOptions=repository=$OUT/$TAG.jfr.d,stackdepth=192"
jfr+=" -XX:StartFlightRecording=name=profile,settings=profile,dumponexit=true,filename=$OUT/$TAG.jfr"
jfr+=",jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=${PERIOD:-1ms}"
jfr+=",jdk.ObjectAllocationSample#throttle=2000/s,jdk.ThreadAllocationStatistics#period=1s"
# lock waits from 1 ms, for JfrProfile's lock-wait section (the profile settings record them from 10 or 20 ms)
jfr+=",jdk.JavaMonitorEnter#threshold=1ms,jdk.ThreadPark#threshold=1ms"
mkdir -p "$OUT/$TAG.jfr.d"
MAPS=$MAPS TRANSFER=$TRANSFER OUT=$OUT TAG=$TAG SERVER_OPTS="$jfr ${SERVER_OPTS:-}" tools/bots/load/load.sh
rm -rf "$OUT/$TAG.jfr.d"
log=$OUT/$TAG.server.txt
mark=$(sed -n 's/.*perf mark at t=\([0-9]*\)s.*/\1/p' "$log" | head -1)
secs=$(sed -n 's/.*SUMMARY seconds=\([0-9]*\).*/\1/p' "$log" | head -1)
ticks=$(sed -n 's/.*SUMMARY.* tick\[n=\([0-9]*\).*/\1/p' "$log" | head -1)
[ -n "$mark" ] && [ -n "$secs" ] || { echo "no perf window in $log"; exit 1; }
period=${PERIOD:-1ms}
java -Xmx3g tools/bots/load/JfrProfile.java "$OUT/$TAG.jfr" "$mark" $(( mark + secs )) "$OUT/$TAG" "${ticks:-0}" "${period%ms}" > "$OUT/$TAG.profile.txt"
echo "profile in $OUT/$TAG.profile.txt"
