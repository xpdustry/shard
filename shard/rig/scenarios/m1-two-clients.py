#!/usr/bin/env python3
"""Writes m1-two-clients.rig, M1 with two stock clients on the 18 default maps: python3 m1-two-clients.py > m1-two-clients.rig.

Shard hosts the 18 maps with `--join round-robin`, so every join lands on the next map in vanilla's order
(Maps.defaultMapNames). bravo's first join takes maze; then each map takes exactly one more join, alternating
alpha and bravo, and the other client follows with `/world <map>` (a transfer, which doesn't move the round robin):
fortress, labyrinth, ..., passage, and maze again at the end. On each map both clients check, on their own client:
  - the world has the map's size, and they see their team's cores (as many as the map has: passage has two);
  - the other player is where its own client says (`expect player ... within 2`);
  - alpha queues a conveyor and bravo a copper wall (on Erekir maps a duct and a beryllium wall) through its own
    unit's build plans, each on the first valid tile 3 to 12 tiles east (alpha) or west (bravo) of it, else on the
    other side; both see both blocks built, one of each more on the team, each near the player that built it;
  - bravo walks, and alpha sees bravo's unit move at least 2 tiles and stand where bravo's client says; then the same
    the other way round;
  - then `exec` records the map in m1-maps.tsv: "clean", or "FAIL" with the first new desync or error lines of either
    client's log (the rig's startup noise aside) and new server ERROR/WARN lines (the two load lines m0.rig excuses
    aside) since the map before. It never fails the step, so every map is recorded; the end-of-run checks fail the run.
"""

MAPS = [("maze", "256x256", 1), ("fortress", "256x256", 1), ("labyrinth", "200x200", 1), ("islands", "256x256", 1),
        ("tendrils", "300x300", 1), ("caldera", "256x256", 1), ("wasteland", "300x300", 1), ("shattered", "350x350", 1),
        ("fork", "250x300", 1), ("triad", "200x200", 1), ("mudFlats", "400x400", 1), ("moltenLake", "400x400", 1),
        ("archipelago", "500x500", 1), ("debrisField", "400x400", 1), ("domain", "494x494", 1), ("veins", "350x200", 1),
        ("glacier", "150x250", 1), ("passage", "500x120", 2)]

def world_id(name):
    """The host's id for a map: vanilla's camelCase name in kebab case (mudFlats -> mud-flats), as its log and /world use."""
    return "".join("-" + c.lower() if c.isupper() else c for c in name)


# The client's script console keeps its globals across joins: these are defined once, on each client.
HELPERS = (
    "m1erekir = function(){ return Vars.state.rules.planet == Packages.mindustry.content.Planets.erekir; }; "
    "m1a = function(){ return m1erekir() ? Blocks.duct : Blocks.conveyor; }; "
    "m1b = function(){ return m1erekir() ? Blocks.berylliumWall : Blocks.copperWall; }; "
    "m1find = function(n){ for(var i = 0; i < Groups.player.size(); i++){ var p = Groups.player.index(i); if(p.plainName() == n) return p; } return null; }; "
    "m1counts = function(){ var d = Vars.player.team().data(); return d.getBuildings(m1a()).size + ',' + d.getBuildings(m1b()).size; }; "
    "m1grew = function(){ var b = m1base.split(','), n = m1counts().split(','); return (n[0] - b[0]) + ',' + (n[1] - b[1]); }; "
    "m1near = function(block, n){ var p = m1find(n); if(p == null) return -1; var s = Vars.player.team().data().getBuildings(block), c = 0; "
    "for(var i = 0; i < s.size; i++) if(s.get(i).within(p.x, p.y, 12 * 8)) c++; return c; }; "
    "m1place = function(block, sign){ var t = Vars.player.team(), px = Vars.player.tileX(), py = Vars.player.tileY(); "
    "for(var side = 0; side < 2; side++) for(var r = 3; r <= 12; r++) for(var d = 0; d <= 2 * r; d++){ var x = px + (side == 0 ? sign : -sign) * r, y = py + (d % 2 == 0 ? d / 2 : -(d + 1) / 2); "
    "if(Packages.mindustry.world.Build.validPlace(block, t, x, y, 0)){ Vars.player.unit().addBuild(new Packages.mindustry.entities.units.BuildPlan(x, y, 0, block)); "
    "return 'queued ' + x + ',' + y; } } return 'nowhere'; }; "
    "m1at = function(n){ var p = m1find(n); return p == null ? null : [p.x, p.y]; }; "
    "m1dst = function(n, from){ var p = m1find(n); if(p == null || from == null) return -1; var dx = p.x - from[0], dy = p.y - from[1]; return Math.sqrt(dx * dx + dy * dy) / 8; }; "
    "'ok'"
)

NOISE = "OpenGL|GL context|GLXBadFBConfig|community servers|cdn.jsdelivr.net"
CLIENT_BAD = r"Missing entity|ID mismatch|[Mm]ismatch|Error reading entity snapshot|[Dd]esync|Exception|\[E\]"
SERVER_OK = r"stalled for [0-9]+ ms|ticks behind; dropping them"
CORES = ("Vars.player.team().cores().select(function(c){ return Vars.world.build(c.tileX(), c.tileY()) == c && "
         "c.block instanceof Packages.mindustry.world.blocks.storage.CoreBlock; }).size")


def tally(name, joiner, follower):
    return (
        "exec s=server/server.log; read a0 b0 s0 < m1.lines 2>/dev/null || { a0=0; b0=0; s0=0; }; "
        "bad=$(for c in alpha:$a0 bravo:$b0; do n=${c%%:*}; o=${c##*:}; "
        f"tail -n +$((o+1)) $n/data/Mindustry/last_log.txt | grep -E '{CLIENT_BAD}' | grep -vE '{NOISE}' | head -3 | sed \"s/^/$n: /\"; done; "
        f"tail -n +$((s0+1)) $s | grep -E 'ERROR|Exception|WARN' | grep -vE '{SERVER_OK}' | head -3); "
        "echo \"$(wc -l < alpha/data/Mindustry/last_log.txt) $(wc -l < bravo/data/Mindustry/last_log.txt) $(wc -l < $s)\" > m1.lines; "
        f"if [ -z \"$bad\" ]; then printf '{name}\\t{joiner} joined, {follower} moved in\\tclean\\n' >> m1-maps.tsv; "
        f"else printf '{name}\\t{joiner} joined, {follower} moved in\\tFAIL\\t%s\\n' \"$(echo $bad)\" >> m1-maps.tsv; fi"
    )


def walk(walker, watcher):
    return f"""{watcher}: expect js "m1from = m1at('{walker}'); m1from != null" /^true$/ 10s
{walker}: mark
{walker}: hold D 700ms
{walker}: hold W 700ms
{walker}: expect moved 2
{watcher}: expect js "m1dst('{walker}', m1from) >= 2" /^true$/ 10s
{watcher}: expect player {walker} within 2 10s
"""


def visit(k):
    name, size, cores = MAPS[k % len(MAPS)]
    name, prev = world_id(name), world_id(MAPS[(k - 1) % len(MAPS)][0])
    joiner, follower = ("alpha", "bravo") if k % 2 == 1 else ("bravo", "alpha")
    arrive = "join" if k == 1 else "rejoin"
    return f"""
# {k:02d}. {name}: {joiner} joins, {follower} moves in from {prev}
{joiner}: {arrive}
wait log server /\\({joiner}\\) has connected to {name} / 30s
{follower}: chat "/world {name}"
wait log server /\\({follower}\\) moves from {prev} to {name}/ 15s
wait log server /\\({follower}\\) receives {name}/ 30s
all: expect js "{HELPERS}" /^ok$/ 10s
all: expect js "Vars.world.width() + 'x' + Vars.world.height()" /^{size}$/ 30s
all: expect js "!Vars.player.dead()" /^true$/ 60s
all: expect js "Vars.player.team().cores().size" /^{cores}(\\.0)?$/ 10s
all: expect js "{CORES}" /^{cores}(\\.0)?$/ 10s
alpha: expect player bravo within 2 20s
bravo: expect player alpha within 2 20s
all: expect js "m1base = m1counts(); m1base" /^\\d+,\\d+$/ 10s
alpha: expect js "m1place(m1a(), 1)" /^queued -?\\d+,-?\\d+$/ 10s
bravo: expect js "m1place(m1b(), -1)" /^queued -?\\d+,-?\\d+$/ 10s
all: expect js "m1grew()" /^1,1$/ 60s
all: expect js "m1near(m1a(), 'alpha') >= 1 && m1near(m1b(), 'bravo') >= 1" /^true$/ 10s
all: shot {k:02d}-{name}
{walk("bravo", "alpha")}{walk("alpha", "bravo")}all: wait 120t
all: expect connected
{tally(name, joiner, follower)}
"""


def main():
    HEADER = "\n".join("#" + (" " + line if line else "") for line in __doc__.strip().splitlines()[2:])
    print(f"""# M1 (ARCHITECTURE.md §10) on two stock clients (L5): both clients on each of the 18 default maps, each seeing the
# other's unit move and the other's blocks, with no desync line. Generated: edit m1-two-clients.py and rerun it
# (python3 rig/scenarios/m1-two-clients.py > rig/scenarios/m1-two-clients.rig).
{HEADER}
#
#   scripts/vm-gradle.sh :server:installDist
#   rig/run.sh --vm "$(cat .vm-id)" --type large --clients 2 --script rig/scenarios/m1-two-clients.rig --server \\
#     'PUSH=/home/user/shard/server/build/install/shard exec "$RIG"/vm/shard-server.sh --join round-robin {" ".join(m[0] for m in MAPS)}'
timeout 75m

# the server listens before it has hosted every world; join once it has
wait port 360s
wait log server /ready: 18 worlds hosted/ 180s
# every world's core full, so the build plans never wait on copper
console fillitems

# bravo's join takes maze, the round robin's first world; it waits there for alpha's first join, which takes fortress
bravo: join
wait log server /\\(bravo\\) has connected to maze / 30s""")
    for k in range(1, len(MAPS) + 1):
        print(visit(k), end="")
    print(r"""
exec cat m1-maps.tsv; [ "$(grep -c '	clean$' m1-maps.tsv)" = 18 ] || { echo "not every map is clean"; exit 1; }
console status
wait log server /Status: 18 worlds, 2 players?/ 10s
assert log server !/Exception|ERROR/ after /ready: 18 worlds hosted/
assert clean all
# No server WARN line, except the two a busy VM makes: the watchdog's "stalled for N ms" and the scheduler's "N ticks
# behind; dropping them" (m0.rig's last assert). Tick lag has its own gates.
assert log server !/^\S+ WARN ++(?!\[tick-watchdog\] \S+ - world \S+ stalled for \d+ ms|\[tick-\d+\] \S+ - world \S+ is \d+ ticks behind; dropping them until it keeps up)/""")


if __name__ == "__main__":
    main()
