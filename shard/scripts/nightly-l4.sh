#!/usr/bin/env bash
# The nightly L4 run (docs/development.md "Nightly L4"): `check` with the slow suites included and the fuzzers on a time budget,
# on this checkout's boat VM (scripts/vm-gradle.sh), which is deleted afterwards. Nothing schedules it; run it from a
# timer or CI on a clean checkout.
#
#   scripts/nightly-l4.sh                   # 10 minutes of fuzzing per direction
#   scripts/nightly-l4.sh --minutes 30      # longer fuzzing
#   scripts/nightly-l4.sh --keep            # keep the VM (scripts/vm-gradle.sh --delete removes it)
#   scripts/nightly-l4.sh -- -Pshard.tags=all   # extra Gradle arguments
#
# The log goes to build/nightly-l4/<UTC time>.log and the test reports to build/vm-reports/. Exits with Gradle's code.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
minutes=10 keep=0 extra=()
while [ $# -gt 0 ]; do
  case "$1" in
    --minutes) minutes=$2; shift 2 ;;
    --keep) keep=1; shift ;;
    --) shift; extra=("$@"); break ;;
    *) echo "usage: scripts/nightly-l4.sh [--minutes N] [--keep] [-- gradle args]" >&2; exit 2 ;;
  esac
done

log="$here/build/nightly-l4/$(date -u +%Y%m%dT%H%M%SZ).log"
mkdir -p "$(dirname "$log")"
set +e
# --continue: one failing suite must not hide the others
"$here/scripts/vm-gradle.sh" --pull check --continue -Pshard.tags=slow -Pshard.fuzzMinutes="$minutes" "${extra[@]}" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
set -e
[ "$keep" = 1 ] || "$here/scripts/vm-gradle.sh" --delete
echo "nightly-l4: exit $rc; log $log; reports in build/vm-reports/"
exit "$rc"
