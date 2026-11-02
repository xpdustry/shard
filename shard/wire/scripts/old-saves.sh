#!/usr/bin/env bash
# Writes the old-revision samples in wire/src/oracleTest/resources/saves/<version>.msav with old vanilla servers
# (OldRevisionsTest). Run it on a boat VM started with --no-env, never on the dev box: it downloads release jars from
# GitHub and runs them, and each server listens on port 6567 inside the VM.
#
#   wire/scripts/old-saves.sh <out dir> v105 v126.2 ...
#
# For each version: download its server-release.jar into a directory of its own, host fortress in sandbox, spawn one
# unit of every type (payload units carry a dagger, and a router where the build has block payloads), a fire and a
# puddle, then save. Needs Java 11 (the image's /usr/lib/jvm/java-11-openjdk-amd64).
set -euo pipefail
out=$(realpath -m "$1"); shift
java=${JAVA11:-/usr/lib/jvm/java-11-openjdk-amd64/bin/java}
mkdir -p "$out"

# one line per js command: the server runs the rest of the line as a script
read -r -d '' spawn <<'JS' || true
var oc = Packages.mindustry.Vars.content, ow = Packages.mindustry.Vars.world, oteam = Packages.mindustry.game.Team.sharded, on = 0, onames = [];
JS
units='oc.units().each(function(t){ if(t.name == "block") return; try{ var x = (20 + (on % 30) * 6) * 8, y = (20 + Math.floor(on / 30) * 6) * 8; on++; var u = t.spawn(oteam, x, y); if(u.addPayload){ u.addPayload(new Packages.mindustry.world.blocks.payloads.UnitPayload(Packages.mindustry.content.UnitTypes.dagger.create(oteam))); try{ u.addPayload(new Packages.mindustry.world.blocks.payloads.BuildPayload(Packages.mindustry.content.Blocks.router, oteam)); }catch(e){} } onames.push(t.name); }catch(e){ onames.push(t.name + "!" + e); } }); print("spawned " + onames.join(" "));'
others='try{ Packages.mindustry.entities.Fires.create(ow.tile(30, 30)); print("fire"); }catch(e){ print("fire! " + e); } try{ Packages.mindustry.entities.Puddles.deposit(ow.tile(32, 32), Packages.mindustry.content.Liquids.water, 50); print("puddle"); }catch(e){ print("puddle! " + e); }'

for version in "$@"; do
  dir=$HOME/old-servers/$version
  rm -rf "$dir" && mkdir -p "$dir"
  if ! curl -fsSL -o "$dir/server-release.jar" "https://github.com/Anuken/Mindustry/releases/download/$version/server-release.jar"; then
    echo "$version: no server-release.jar"; continue
  fi
  (
    cd "$dir"
    { sleep 25; echo "host fortress sandbox"; sleep 15
      echo "js $spawn $units"; sleep 3
      echo "js $spawn $others"; sleep 3
      echo "save 1"; sleep 8; echo "exit"; } | timeout 180 "$java" -Xmx768m -jar server-release.jar > server.log 2>&1 || true
  )
  if [ -s "$dir/config/saves/1.msav" ]; then
    cp "$dir/config/saves/1.msav" "$out/$version.msav"
    echo "$version: $(grep -o 'spawned.*' "$dir/server.log" | head -c 300)"
  else
    echo "$version: no save; log tail:"; tail -15 "$dir/server.log"
  fi
done
