#!/usr/bin/env bash
# The vanilla v160.7 dedicated server, for rig runs: `--server vanilla`, `--server 'vanilla serpulo/groundZero'`, or
# `--server 'vanilla serpulo/groundZero -- host Ground_Zero'`. Arguments before `--` are maps inside the jar
# (maps/<arg>.msav), installed as custom maps first: sector maps such as groundZero aren't in the server's `host`
# list otherwise. Arguments after `--` are console commands to run at startup (comma-separated, as vanilla takes
# them). `host` matches the map's name tag with `_` for spaces: groundZero's tag is "Ground Zero".
set -euo pipefail
jar=$VANILLA/server-release.jar
mkdir -p config/maps
while [ $# -gt 0 ] && [ "$1" != -- ]; do
  unzip -p "$jar" "maps/$1.msav" > "config/maps/$(basename "$1").msav"
  shift
done
[ "${1:-}" = -- ] && shift
exec "${JAVA:-java}" --enable-native-access=ALL-UNNAMED -jar "$jar" "$@"
