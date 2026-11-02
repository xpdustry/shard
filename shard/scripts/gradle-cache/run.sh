#!/usr/bin/env bash
# Shares the Gradle build cache server (server.py) through sloppa, a password-protected HTTPS tunnel to a loopback
# port; without sloppa, put server.py behind your own reverse proxy with basic auth. The password is in
# ~/.cache/shard/gradle-cache/credentials. The systemd user unit (shard-gradle-cache.service) runs this and
# restarts it every time sloppa's 24 h TTL ends. Run it by hand only to try the server: it blocks.
#
# sloppa has no way to read the password except its command line, so it is visible to this user's `ps` for as long as
# the unit runs. The file, the unit and the logs never contain it.
set -euo pipefail
dir=${SHARD_GRADLE_CACHE_HOME:-$HOME/.cache/shard/gradle-cache}
here=$(cd "$(dirname "$0")" && pwd)
creds=$dir/credentials
[ -f "$creds" ] || { echo "gradle-cache: no $creds (run install.sh)" >&2; exit 1; }
# shellcheck disable=SC1090
. "$creds"
exec sloppa preview run --name shard-gradle-cache --password "$SHARD_CACHE_PASSWORD" --ttl 24h -- \
  python3 -I "$here/server.py" --host '{host}' --port '{port}' --dir "$dir/entries" --max-gb "${SHARD_GRADLE_CACHE_GB:-15}"
