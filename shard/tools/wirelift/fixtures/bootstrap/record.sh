#!/usr/bin/env bash
# Records the bootstrap fixtures (wire/src/test/resources/bootstrap/v<build>.txt) from vanilla server jars. Old jars are
# downloads, so they only run on a throwaway boat VM, which this creates and deletes:
#
#   tools/wirelift/fixtures/bootstrap/record.sh 146=dl-v146/server-release.jar 159=dl-v159/server-release.jar \
#       160.7=~/.cache/shard/vanilla/v160.7/server-release.jar
#
# Get the old jars from https://github.com/Anuken/Mindustry/releases (v146, v159) into their own empty directories.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(cd "$here/../../../.." && pwd)/wire/src/test/resources/bootstrap
boat=${BOAT:-$(command -v boat || { echo "boat not found: install it or set BOAT" >&2; exit 1; })}
[ $# -gt 0 ] || { sed -n '2,8p' "$0"; exit 2; }

id=$("$boat" new --no-env --from "${SHARD_VM_IMAGE:-shard-runner}" --type small --name "shard-bootstrap-fixtures-$$" --no-update --json \
  | python3 -I -c 'import json,sys
for l in sys.stdin:
    try: e=json.loads(l)
    except ValueError: continue
    if e.get("event")=="ready": print(e["id"]); break')
[ -n "$id" ] || { echo "record.sh: could not create a VM" >&2; exit 1; }
trap '"$boat" delete "$id" --yes --no-update >/dev/null && echo "deleted VM $id" >&2' EXIT
echo "recording on VM $id" >&2

mkdir -p "$out"
"$boat" scp "$here/RecordConnect.java" "$id:/home/user/RecordConnect.java" --no-update >/dev/null
for spec in "$@"; do
  build=${spec%%=*}
  jar=${spec#*=}
  "$boat" scp "$jar" "$id:/home/user/v$build.jar" --no-update >/dev/null
  "$boat" exec "$id" --no-update --json -- bash -lc "cd ~ && java -cp v$build.jar RecordConnect.java" \
    | python3 -I -c 'import json,sys
r=json.loads(sys.stdin.read().strip().splitlines()[-1])
sys.stderr.write(r.get("stderr",""))
if r.get("exitCode")!=0: sys.exit(1)
sys.stdout.write(r["stdout"])' > "$out/v$build.txt"
  echo "wrote $out/v$build.txt" >&2
done
