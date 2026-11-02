#!/usr/bin/env bash
# Installs the shared Gradle build cache on this machine (the first install needs SHARD_CACHE_URL, where run.sh's
# tunnel serves it, ending in /cache/): copies server.py and run.sh to ~/.cache/shard/gradle-cache/bin
# (so the unit survives this worktree being deleted), makes the credentials file once (mode 600, never printed), and
# installs and starts the systemd user unit. Safe to run again: it keeps the credentials and the stored entries.
#
#   scripts/gradle-cache/install.sh          # install or update, then (re)start
#   scripts/gradle-cache/install.sh --stop   # stop and disable the unit (entries are kept)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
dir=$HOME/.cache/shard/gradle-cache
unitdir=$HOME/.config/systemd/user

if [ "${1:-}" = --stop ]; then
  systemctl --user disable --now shard-gradle-cache.service
  exit 0
fi

mkdir -p "$dir/bin" "$dir/entries" "$unitdir"
install -m 755 "$here/server.py" "$here/run.sh" "$dir/bin/"
if [ ! -f "$dir/credentials" ]; then
  umask 077
  pw=$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')
  cat > "$dir/credentials" <<EOF
SHARD_CACHE_URL=${SHARD_CACHE_URL:?set SHARD_CACHE_URL to the URL that run.sh serves the cache at, ending in /cache/}
SHARD_CACHE_USER=shard
SHARD_CACHE_PASSWORD=$pw
EOF
fi
chmod 600 "$dir/credentials"
install -m 644 "$here/shard-gradle-cache.service" "$unitdir/"
systemctl --user daemon-reload
systemctl --user enable shard-gradle-cache.service >/dev/null 2>&1 || true
systemctl --user restart shard-gradle-cache.service
sleep 3
systemctl --user is-active shard-gradle-cache.service
