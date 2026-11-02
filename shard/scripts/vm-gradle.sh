#!/usr/bin/env bash
# Runs Gradle on a boat VM against this checkout, so the shared dev box keeps its RAM.
#
#   scripts/vm-gradle.sh :sim:test --tests '*Conveyor*'   # sync sources, run, stream the output
#   scripts/vm-gradle.sh --pull :sim:test                 # same, then copy build reports and JUnit XML to build/vm-reports/
#   scripts/vm-gradle.sh --affected                       # check, but only the test classes this change can affect
#   scripts/vm-gradle.sh --affected --tests-only          # the same without the non-test checks
#   scripts/vm-gradle.sh --shell 'ls ~/shard'             # run any command in the VM's copy (synced, locked)
#   scripts/vm-gradle.sh --peek 'ls build/vm-reports'     # read-only: no sync, no lock
#   scripts/vm-gradle.sh --wait :sim:test                 # queue behind a run that holds the VM instead of failing
#   scripts/vm-gradle.sh --delete                         # delete this checkout's VM (do this when you finish)
#
# One run at a time per VM: a run holds the VM's lock from its sync to its end, and a second one fails at once naming
# the holder (exit 75), or waits with --wait (see locked() below).
#
# One VM per checkout, created on first use from the `shard-runner` image and remembered in `.vm-id`. Its Gradle
# daemon and build directories stay warm between runs. Sources are synced with rsync --delete, so deleted files go
# away remotely too. Gradle and every --shell command run on JDK 27 (/opt/jdk27): a VM made from an image without it
# gets the pinned Temurin build below, checked against its SHA-256, on its first run.
#
# --affected (scripts/testmap.py): every test JVM on the VM logs the classes it loads, and each run folds the logs into
# the VM's test map (~/.shard-testmap). --affected compiles, asks the map which test classes loaded a class whose bytes
# changed since they last ran (or are new, or failed), and runs `check` with only those. It is the inner loop's check,
# not the gate: run a full check (`cleanTest cleanOracleTest check --continue --no-build-cache`) before main moves.
# A fresh VM has no map, so its first --affected runs everything and records it.
#
# Env (all optional):
#   SHARD_VM_IDFILE=path     where this VM's id is remembered (default .vm-id), to run several VMs from one checkout
#   SHARD_VM_IMAGE=name      the boat image new VMs start from (default shard-runner: Linux with JDK 25 in /opt/jdk25,
#                            python3, rsync, and the vanilla v160.7 jars in ~/vanilla/v160.7/)
#   SHARD_VM_TYPE=small|default|large|xlarge   the VM a new checkout gets (default: large, 8 vCPU / 16 GB)
#   SHARD_TEST_FORKS=N       test JVMs per test task. Default: picked on the VM from its CPUs and memory (see forks_expr)
#   SHARD_CACHE=off          don't use the shared build cache (below); SHARD_CACHE_PUSH=false reads it but doesn't store
#   SHARD_CACHE_CREDENTIALS  the file with SHARD_CACHE_URL/USER/PASSWORD (default ~/.cache/shard/gradle-cache/credentials)
#
# The shared build cache: scripts/gradle-cache/ serves one from the dev box, through sloppa. When its credentials file
# exists, every command on the VM (Gradle and --shell alike) gets SHARD_CACHE_URL, _USER, _PASSWORD and
# SHARD_CACHE_PUSH=true in its environment (settings.gradle.kts turns them into a remote build cache), so the first VM
# to compile or test something stores it and the next gets `FROM-CACHE`. They travel in the exec command and are never
# written to the VM's disk, the checkout (rsync --delete would drop them) or this script's output. Without the file the
# build is the local one.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)            # the shard/ directory of this checkout
idfile=${SHARD_VM_IDFILE:-$here/.vm-id}   # one VM per checkout; SHARD_VM_IDFILE keeps several side by side
boat=${BOAT:-$(command -v boat || { echo "boat not found: install it or set BOAT" >&2; exit 1; })}

# Boat stops a VM when its lifetime runs out. Each run resumes a stopped VM (its daemon and build dirs survive in the
# snapshot) and resets the lifetime to $ttl, so a VM stops on its own $ttl after its last use.
ttl=7200

# Eclipse Temurin 27 (Oracle's builds have no Shenandoah). The checksum is Adoptium's (api.adoptium.net and the
# release's .sha256.txt); its GPG signature checked out against Adoptium's key 3B04D753C9050D9A5D343F39843C48A565F8F04B.
jdk_url=https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.0.2%2B1/OpenJDK27U-jdk_x64_linux_hotspot_27.0.1.0.2_1.tar.gz
jdk_sha256=30cb129f6cf299c852cec0feca9ea2a596d2ee9f26d8605d3a253951f6c8b20f
# Installs it as /opt/jdk27 and the login shells' Java ($1 url, $2 checksum), unless the VM has it, then stops the
# Gradle daemons started on the older JDK. It refuses to run anywhere but a shard-runner VM (box-node-* host, user
# `user`, /opt/jdk25) that vm-gradle.sh named with SHARD_ON_VM=1.
install_jdk='[ -x /opt/jdk27/bin/java ] && exit 0
  case "${SHARD_ON_VM:-}:$(hostname):$(id -un)" in 1:box-node-*:user) ;; *) false ;; esac && [ -d /opt/jdk25 ] \
    || { echo "vm-gradle: not a shard-runner VM, not installing JDK 27" >&2; exit 1; }
  set -e; echo "vm-gradle: installing JDK 27" >&2
  curl -fsSL --retry 3 -o ~/jdk27.tar.gz "$1"
  echo "$2  $HOME/jdk27.tar.gz" | sha256sum -c --quiet -
  sudo rm -rf /opt/jdk27.part && sudo mkdir /opt/jdk27.part
  sudo tar -xzf ~/jdk27.tar.gz -C /opt/jdk27.part --strip-components=1 && sudo mv /opt/jdk27.part /opt/jdk27
  rm ~/jdk27.tar.gz
  printf "export JAVA_HOME=/opt/jdk27\nexport PATH=/opt/jdk27/bin:\$PATH\n" | sudo tee /etc/profile.d/zz-jdk27.sh >/dev/null
  if [ -x ~/shard/gradlew ]; then (cd ~/shard && ./gradlew -q --stop) || true; fi'
jdk27='export JAVA_HOME=/opt/jdk27 PATH=/opt/jdk27/bin:$PATH'

# The VM's state from `boat info`, or "missing" when Boat says it doesn't exist. Fails on anything else (the API
# unreachable): an outage must never look like a deleted VM, or the next run would orphan it and lose .vm-id.
vm_state() {
  local out
  out=$("$boat" info "$1" --json --no-update 2>&1) || true
  python3 -c 'import json,sys
try: d=json.loads(sys.argv[1])
except ValueError: sys.exit(1)
if "sandbox" in d: print(d["sandbox"]["state"])
elif d.get("code")=="not_found": print("missing")
else: sys.exit(1)' "$out"
}

vm_id() {
  if [ -s "$idfile" ]; then
    local id state
    id=$(cat "$idfile")
    state=$(vm_state "$id") || { echo "vm-gradle: Boat API unreachable (VM $id); retry later. Keeping .vm-id." >&2; exit 1; }
    if [ "$state" != missing ]; then
      if [ "$state" = stopped ]; then
        "$boat" resume "$id" --no-env --ttl "$ttl" --no-update --json >/dev/null \
          || { echo "vm-gradle: could not resume VM $id; retry later. Keeping .vm-id." >&2; exit 1; }
        echo "vm-gradle: resumed VM $id" >&2
      else
        "$boat" extend "$id" --ttl "$ttl" --no-update --json >/dev/null 2>&1 || true
      fi
      echo "$id"; return
    fi
    echo "vm-gradle: VM $id no longer exists; creating a new one" >&2
  fi
  local name="shard-gradle-$(basename "$(dirname "$here")")-$$"
  "$boat" new --no-env --from "${SHARD_VM_IMAGE:-shard-runner}" --type "${SHARD_VM_TYPE:-large}" --name "$name" --ttl "$ttl" --no-update --json \
    | python3 -c 'import json,sys
for l in sys.stdin:
    try: e=json.loads(l)
    except ValueError: continue
    if e.get("event")=="ready": print(e["id"]); break' > "$idfile" || true
  [ -s "$idfile" ] || { echo "vm-gradle: could not create a VM" >&2; rm -f "$idfile"; exit 1; }
  echo "vm-gradle: created VM $(cat "$idfile")" >&2
  cat "$idfile"
}

# Print stdout/stderr frames of `boat exec --stream --json` as plain text; exit with the remote exit code.
stream() {
  python3 -u -c 'import json,sys
code=1
for l in sys.stdin:
    try: f=json.loads(l)
    except ValueError: sys.stdout.write(l); continue
    t=f.get("type")
    if t=="stdout": sys.stdout.write(f.get("data",""))
    elif t=="stderr": sys.stderr.write(f.get("data",""))
    elif t=="exit": code=f.get("exitCode") if f.get("exitCode") is not None else 1
    sys.stdout.flush()
sys.exit(code)'
}

# Test forks when SHARD_TEST_FORKS is unset, worked out on the VM: 3 per 8 vCPUs (rounded up), and at most one per 2 GB
# of memory beyond the first 2 (a fork is a 1 GB heap, an oracle fork 768 MB, and vanilla's native memory). Measured on
# a large VM (8 vCPU): 3, 4, 6 and 8 forks all finished the test phase in 390-500 s, because the JVMs already fill the
# CPUs (load average 13), and runs above 3 forks failed load-sensitive tests more often. More forks need more vCPUs.
forks_expr='forks=${SHARD_TEST_FORKS:-$(c=$(nproc); m=$(awk "/MemTotal/{print int(\$2/1048576)}" /proc/meminfo); f=$(( (c*3+7)/8 )); [ $f -gt $(( (m-2)/2 )) ] && f=$(( (m-2)/2 )); [ $f -lt 1 ] && f=1; echo $f)}'

# The shared build cache's variables for the VM, as `export ...;` ("" when off or there are no credentials).
cache_env() {
  local creds=${SHARD_CACHE_CREDENTIALS:-$HOME/.cache/shard/gradle-cache/credentials}
  [ "${SHARD_CACHE:-on}" = off ] || [ ! -r "$creds" ] && return 0
  ( . "$creds"
    printf 'export SHARD_CACHE_URL=%q SHARD_CACHE_USER=%q SHARD_CACHE_PASSWORD=%q SHARD_CACHE_PUSH=%q; ' \
      "$SHARD_CACHE_URL" "$SHARD_CACHE_USER" "$SHARD_CACHE_PASSWORD" "${SHARD_CACHE_PUSH:-true}" )
}

remote() {
  local env; env=$(cache_env)
  local set_forks=; [ -z "${SHARD_TEST_FORKS:-}" ] || set_forks="SHARD_TEST_FORKS=$(printf %q "$SHARD_TEST_FORKS"); "
  "$boat" exec "$1" --timeout 0 --stream --json --no-update -- bash -lc "$jdk27; ${set_forks}$forks_expr; export SHARD_TEST_FORKS=\$forks SHARD_TESTMAP=1; $env$2" | stream
}

# Each run pushes its sources to a directory of its own, so two runs' pushes never mix; the locked part below moves
# them into ~/shard.
incoming=/home/user/shard-incoming-$$-$(date +%s)
push_sources() {
  local id=$1
  "$boat" push "$id" "$here" --to "$incoming" --no-update --json >/dev/null
  # The image keeps the vanilla jars in ~/vanilla; the build looks for them in ~/.cache/shard/vanilla.
  remote "$id" "SHARD_ON_VM=1 bash -c $(printf %q "$install_jdk") - $jdk_url $jdk_sha256"' && mkdir -p ~/shard ~/.cache/shard && ln -sfn ~/vanilla ~/.cache/shard/vanilla'
}

# One run at a time per VM: a second run's rsync and recompile would swap class files under the first one's Gradle
# (W5.6 and W5.2 saw ~30 executor failures and an exit code that didn't match the XML). locked <what> <body> prints the
# remote command that takes an exclusive flock on ~/shard/.vm-gradle.lock, records who holds it, syncs the pushed
# sources into ~/shard and runs <body> there, all under the lock. Busy: it fails at once (exit 75) naming the holder,
# or with --wait queues behind it. The lock and holder files are kept out of rsync --delete.
locked() {
  local who; who="$1 (vm-gradle pid $$ from $here, started $(date -u +%FT%TZ))"
  cat <<SH
exec 9>>~/shard/.vm-gradle.lock || exit 1
if ! flock -n 9; then
  if [ $wait = 1 ]; then
    echo "vm-gradle: VM busy, waiting for: \$(cat ~/shard/.vm-gradle.holder 2>/dev/null)" >&2; flock 9
  else
    echo "vm-gradle: refusing to run, this VM is busy with: \$(cat ~/shard/.vm-gradle.holder 2>/dev/null). Wait for it to finish, or pass --wait to queue behind it." >&2
    rm -rf $incoming; exit 75
  fi
fi
printf '%s\n' $(printf %q "$who") > ~/shard/.vm-gradle.holder
rsync -a --delete --include "**/src/**/build/" --exclude build/ --exclude .gradle/ --exclude .kotlin/ --exclude .vm-id \
  --exclude .vm-gradle.lock --exclude .vm-gradle.holder $incoming/ ~/shard/ && rm -rf $incoming || exit 1
cd ~/shard && $2
SH
}

wait=0 pull=0
while :; do
  case "${1:-}" in
    --wait) shift; wait=1 ;;
    --pull) shift; pull=1 ;;
    *) break ;;
  esac
done

case "${1:-}" in
  --delete)
    [ -s "$idfile" ] && "$boat" delete "$(cat "$idfile")" --yes --no-update >/dev/null && echo "deleted $(cat "$idfile")"
    rm -f "$idfile"; exit 0 ;;
  --peek)
    # read-only: no sync, no lock (look at reports or logs while a run goes on)
    shift; id=$(vm_id); remote "$id" "cd ~/shard && $*"; exit $? ;;
  --shell)
    shift; id=$(vm_id); push_sources "$id"; remote "$id" "$(locked "--shell $*" "$*")"; exit $? ;;
esac

# After every Gradle run: fold the test JVMs' class logs into the test map (scripts/testmap.py update), and with --pull
# pack the reports, still under the lock. --full: a run that names no --tests asked for every test class it ran.
after='; rc=$?; python3 scripts/testmap.py update --since "$since" $full || true'
[ "$pull" = 0 ] || after+='; find . \( -path "*/build/reports" -o -path "*/build/test-results" \) -type d | tar -czf /home/user/reports.tgz -T - 2>/dev/null'
after+='; exit $rc'
id=$(vm_id)
push_sources "$id"
set +e
if [ "${1:-}" = --affected ]; then
  shift
  goal=check; [ "${1:-}" = --tests-only ] && { shift; goal=; }
  args=; [ $# -eq 0 ] || args=$(printf '%q ' "$@")
  remote "$id" "$(locked "--affected $*" "since=\$(date +%s) && ./gradlew --console=plain -q testClasses oracleTestClasses \
    && selected=\$(python3 scripts/testmap.py select) && echo \"vm-gradle: affected: \$selected\" \
    && full= && ./gradlew --console=plain $goal --continue -Pshard.affected=true \$selected $args$after")"
else
  args=; [ $# -eq 0 ] || args=$(printf '%q ' "$@")
  case " $* " in *" --tests "*) full= ;; *) full=--full ;; esac
  remote "$id" "$(locked "$*" "since=\$(date +%s) && full=$full && ./gradlew --console=plain $args$after")"
fi
rc=$?
set -e
if [ "$pull" = 1 ] && [ "$rc" != 75 ]; then
  mkdir -p "$here/build/vm-reports"
  "$boat" scp "$id:/home/user/reports.tgz" "$here/build/vm-reports/reports.tgz" --no-update >/dev/null 2>&1 \
    && tar -xzf "$here/build/vm-reports/reports.tgz" -C "$here/build/vm-reports" && echo "reports in build/vm-reports/"
fi
exit $rc
