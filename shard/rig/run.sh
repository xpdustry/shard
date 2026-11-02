#!/usr/bin/env bash
# One rig run from the dev box: a boat VM (`shard-runner`, --no-env), a server, N vanilla clients, a script, the
# artifacts pulled back, the VM deleted. Nothing graphical runs on this machine.
#
#   rig/run.sh --server vanilla --clients 2 --script rig/scenarios/vanilla-groundzero.rig
#   rig/run.sh --push server/build/install/server --server '"$PUSH"/bin/server' --clients 2 --script rig/scenarios/join-walk.rig
#
# Flags:
#   --server CMD     shell command that starts the server on the VM (`vanilla` = vanilla server-release.jar)
#   --clients N      vanilla clients, named alpha, bravo, charlie... (default 1)
#   --script FILE    the .rig script (any path; it is copied over)
#   --out DIR        local artifact directory (default ~/.cache/shard/scratch/rig/runs/<time>-<script>)
#   --push DIR       copy a local directory (a server build) to the VM; the server command sees it as $PUSH
#   --vm ID          use this VM and leave it running (e.g. the one in shard/.vm-id from scripts/vm-gradle.sh)
#   --keep           keep the VM this run creates; later runs reuse it (rig/.vm-id) until `--delete-vm`
#   --type T         VM size for a new VM: small, default (4 cores, 8 GB) or large (default: default)
#   --expect V       pass (default) or fail: the exit code is 0 when the verdict is V
#   --port P, --size WxH   server port (default 6567) and client screen size (default 1280x720)
#   --server-cpus L, --client-cpus L   pin the server's command and each client (Xvfb, JVM, input) to CPU lists
#                    (taskset -c), so clients starting or rendering can't take the server's CPUs
#   --delete-vm      delete the VM remembered by --keep, then exit
set -euo pipefail
rig=$(cd "$(dirname "$0")" && pwd)
boat=${BOAT:-$(command -v boat || { echo "boat not found: install it or set BOAT" >&2; exit 1; })}
scratch=$HOME/.cache/shard/scratch/rig
idfile=$rig/.vm-id

server='' clients=1 script='' out='' push='' vm='' keep=0 type=default expect=pass extra=()
while [ $# -gt 0 ]; do
  case $1 in
    --server) server=$2; shift 2 ;;
    --clients) clients=$2; shift 2 ;;
    --script) script=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --push) push=$2; shift 2 ;;
    --vm) vm=$2; shift 2 ;;
    --keep) keep=1; shift ;;
    --type) type=$2; shift 2 ;;
    --expect) expect=$2; shift 2 ;;
    --port|--size|--server-cpus|--client-cpus) extra+=("$1" "$2"); shift 2 ;;
    --delete-vm)
      [ -s "$idfile" ] && "$boat" delete "$(cat "$idfile")" --yes --no-update >/dev/null && echo "deleted $(cat "$idfile")"
      rm -f "$idfile"; exit 0 ;;
    -h|--help) sed -n '2,/^set -e/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "rig: unknown flag $1 (see --help)" >&2; exit 2 ;;
  esac
done
[ -n "$server" ] && [ -f "$script" ] || { echo "rig: need --server and an existing --script (see --help)" >&2; exit 2; }
case $expect in pass|fail) ;; *) echo "rig: --expect is pass or fail" >&2; exit 2 ;; esac

# A syntax error should cost a second here, not a VM.
if command -v java >/dev/null; then
  java -Xmx64m "$rig/src/com/xpdustry/shard/rig/Script.java" "$script" >/dev/null || exit 2
fi

runid=$(date +%Y%m%d-%H%M%S)-$(basename "$script" .rig)
out=${out:-$scratch/runs/$runid}
mkdir -p "$out"

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
remote() { "$boat" exec "$id" --timeout 0 --stream --json --no-update -- bash -lc "$1" | stream; }
alive() { [ -n "$1" ] && "$boat" info "$1" --json --no-update >/dev/null 2>&1; }

own=0
if [ -n "$vm" ]; then
  id=$vm
elif [ -s "$idfile" ] && alive "$(cat "$idfile")"; then
  id=$(cat "$idfile")
else
  id=$("$boat" new --no-env --from "${SHARD_VM_IMAGE:-shard-runner}" --type "$type" --name "r3-rig-$runid" --no-update --json \
    | python3 -c 'import json,sys
for l in sys.stdin:
    try: e=json.loads(l)
    except ValueError: continue
    if e.get("event")=="ready": print(e["id"]); break')
  [ -n "$id" ] || { echo "rig: could not create a VM" >&2; exit 1; }
  if [ "$keep" = 1 ]; then echo "$id" > "$idfile"; else own=1; fi
fi
[ "$own" = 1 ] && trap '"$boat" delete "$id" --yes --no-update >/dev/null 2>&1 && echo "rig: deleted VM $id"' EXIT
echo "rig: VM $id, run $runid"

remote 'rm -rf ~/rig ~/rig-push ~/rig-scripts && mkdir -p ~/rig-push ~/rig-scripts ~/rig-runs' >/dev/null
"$boat" push "$id" "$rig" --to /home/user/rig --no-update --json >/dev/null
"$boat" scp "$script" "$id:/home/user/rig-scripts/$(basename "$script")" --no-update >/dev/null
pushflag=()
if [ -n "$push" ]; then
  "$boat" push "$id" "$push" --to "/home/user/rig-push/$(basename "$push")" --no-update --json >/dev/null
  pushflag=(--push "/home/user/rig-push/$(basename "$push")")
fi

args=$(printf '%q ' --server "$server" --clients "$clients" --script "/home/user/rig-scripts/$(basename "$script")" \
  --out "/home/user/rig-runs/$runid" "${pushflag[@]}" "${extra[@]}")
set +e
remote "chmod +x ~/rig/vm/run.sh && ~/rig/vm/run.sh $args"
rc=$?
remote "tar -czf ~/rig-runs/$runid.tgz -C ~/rig-runs $runid" >/dev/null
"$boat" scp "$id:/home/user/rig-runs/$runid.tgz" "$out/../$runid.tgz" --no-update >/dev/null
set -e
tar -xzf "$out/../$runid.tgz" -C "$out" --strip-components=1 && rm -f "$out/../$runid.tgz"

verdict=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["verdict"])' "$out/verdict.json" 2>/dev/null || echo none)
echo "rig: verdict $verdict (driver exit $rc), expected $expect"
echo "rig: artifacts in $out"
[ "$verdict" = "$expect" ]
