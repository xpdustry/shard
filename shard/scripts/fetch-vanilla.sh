#!/usr/bin/env bash
# Downloads vanilla v160.7's release jars from Mindustry's GitHub releases into ~/.cache/shard/vanilla/v160.7/ and
# checks their SHA-256. Gradle downloads the server jar by itself; the scripts outside Gradle (rig/, the replay and
# wirelift recorders, scripts/testmap.py) read the jars from this directory.
#
#   scripts/fetch-vanilla.sh            # server-release.jar (19 MB)
#   scripts/fetch-vanilla.sh --client   # also Mindustry.jar, the desktop client (89 MB), for rig/
set -euo pipefail
version=v160.7
dir=${SHARD_VANILLA_DIR:-$HOME/.cache/shard/vanilla/$version}
jars=("server-release.jar f7b899a4038b95b14a824d5f8993984f79d73baa3254c385b6538327f9c0befd")
[ "${1:-}" = --client ] && jars+=("Mindustry.jar 36d94941a1639478ad1d9be25a9d802c6f77552f400c6da0338b7b019f63a0d1")

mkdir -p "$dir"
for entry in "${jars[@]}"; do
  read -r name sha <<<"$entry"
  if [ -f "$dir/$name" ] && echo "$sha  $dir/$name" | sha256sum -c --quiet - 2>/dev/null; then
    echo "$dir/$name: present"; continue
  fi
  curl -fsSL --retry 3 -o "$dir/$name.part" "https://github.com/Anuken/Mindustry/releases/download/$version/$name"
  echo "$sha  $dir/$name.part" | sha256sum -c --quiet - || { rm -f "$dir/$name.part"; echo "$name: bad SHA-256" >&2; exit 1; }
  mv "$dir/$name.part" "$dir/$name"
  echo "$dir/$name: downloaded"
done
