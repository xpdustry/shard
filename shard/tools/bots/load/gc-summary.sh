#!/usr/bin/env bash
# Sums up a -Xlog:gc file over a window of JVM uptime (perf's t=): the collector, its pauses (count, longest, total)
# and the heap after each cycle, whose minimum is the live set plus what the cycles left behind.
#   tools/bots/load/gc-summary.sh <gc.txt> [from-s] [to-s]
set -euo pipefail
awk -v from="${2:-0}" -v to="${3:-1e9}" '
  /\] Using / && !gc { gc = $0; sub(/.*Using /, "", gc) }
  /GC mode: / && !mode { mode = $0; sub(/.*GC mode: /, "", mode); gc = gc " (" mode ")" }
  { t = substr($1, 2) + 0 }
  t < from || t > to { next }
  / Pause / && /ms$/ { ms = $NF; sub(/ms$/, "", ms); ms += 0; n++; total += ms; if(ms > max){ max = ms; worst = $0 } }
  / Pause (Full|Degenerated)/ { bad++ }
  match($0, /[0-9]+M->[0-9]+M\([0-9]+M\)/) {
    split(substr($0, RSTART, RLENGTH), a, /M->|M\(|M\)/)
    after[$2] = a[2]; heap = a[3]
  }
  END {
    k = 0; for(id in after) v[++k] = after[id] + 0
    for(i = 1; i <= k; i++) for(j = i + 1; j <= k; j++) if(v[j] < v[i]){ x = v[i]; v[i] = v[j]; v[j] = x }
    printf "GC %s, %d-%ds: %d pauses, longest %.1f ms, total %.1f ms, %d full or degenerated", gc, from, to, n, max, total, bad
    if(k == 0) print "; no cycles"
    else printf "; heap after %d cycles: min %d MB, median %d MB, max %d MB of %d MB\n", k, v[1], v[int((k + 1) / 2)], v[k], heap
    if(worst != "") print "  longest: " worst
  }' "$1"
