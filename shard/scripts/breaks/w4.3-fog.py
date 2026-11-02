"""W4.3's one-line breaks of fog (WP73): the redraw cadence and radii, the static pushes, the turret and radar
refreshes, unit deaths, what targeting and snapshots hide, and the save chunk, each applied alone to a clean checkout
and run against FogOracleTest. A break must fail it. A clean run comes first, and must pass.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.3-fog.py');
the results, as committed, are in w4.3-fog.log.
"""
import glob, html, os, re, subprocess, sys

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

FOG = 'sim/src/main/java/com/xpdustry/shard/sim/fog/FogControl.java'
TURRET = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/turret/TurretBuild.java'
RADAR = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/defense/Radar.java'
UNIT = 'sim/src/main/java/com/xpdustry/shard/sim/unit/Unit.java'
TARGETS = 'sim/src/main/java/com/xpdustry/shard/sim/building/ChunkTargets.java'
SNAP = 'compat/src/main/java/com/xpdustry/shard/compat/v160/sync/EntitySnapshots.java'

FIXTURES = ['FogOracleTest']
U = ' ' * 20  # the unit loop's indent

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    ('cadence: a redraw every 2 ticks', FOG, 'DYNAMIC_TICKS = 3;', 'DYNAMIC_TICKS = 2;'),
    ('dynamic radius: not one wider', FOG,
     'circle(data.write, x(event), y(event), rad + 1);', 'circle(data.write, x(event), y(event), rad);'),
    ('unit radius: rounded, not truncated', FOG,
     'long event = event(tx, ty, (int)radius, team);', 'long event = event(tx, ty, Mathf.round(radius), team);'),
    ('unit moves: no static push', FOG,
     'pushEvent(rules, event);\n' + U + 'unit.lastFogPos = pos;', 'unit.lastFogPos = pos;'),
    ('placed source: no static push', FOG,
     'if(rules.staticFog) pushEvent(rules, event(build.tileX(), build.tileY(), Mathf.round(build.fogRadius()), build.team));\n    }\n\n    /** {@code UnitDestroyEvent}',
     '}\n\n    /** {@code UnitDestroyEvent}'),
    ('visibility: tile truncated, not rounded', FOG,
     'Math.round(x / 8f), Math.round(y / 8f)', '(int)(x / 8f), (int)(y / 8f)'),
    ('static chunk: runs of 128', FOG,
     'while(consecutives < 127 && pos < size){', 'while(consecutives < 128 && pos < size){'),
    ('turret: no refresh on an ammo change', TURRET,
     'com.xpdustry.shard.sim.fog.FogControl.forceUpdate(this);', '// no refresh'),
    ('radar: no refresh as it grows', RADAR,
     'com.xpdustry.shard.sim.fog.FogControl.forceUpdate(this);', '// no refresh'),
    ('unit death: no redraw', UNIT,
     'com.xpdustry.shard.sim.fog.FogControl.unitDestroyed(world(), team);', '// no redraw'),
    ('targets: every building discovered', TARGETS,
     'return com.xpdustry.shard.sim.fog.FogControl.isDiscovered(building, viewer);', 'return true;'),
    ('snapshots: shooting units hidden too', SNAP,
     '!unit.isShooting && unit.inFogTo(team)', 'unit.inFogTo(team)'),
]

def results():
    out = {}
    for x in glob.glob('compat/build/test-results/oracleTest/*.xml'):
        text = open(x).read()
        suite = re.search(r'<testsuite name="[^"]*\.(\w+)" tests="(\d+)"', text)
        fails = [html.unescape(m.group(1)).replace('\n', ' | ')[:260] for m in re.finditer(r'<failure message="(.*?)"', text, re.S)]
        out[suite.group(1)] = (int(suite.group(2)), fails)
    return out

only = sys.argv[1:]
for name, path, original, broken in BREAKS:
    if only and not any(o in name for o in only):
        continue
    src = None
    if path:
        src = open(path).read()
        if src.count(original) != 1:
            print(f'SKIP {name}: the original is in {path} {src.count(original)} times', flush=True)
            continue
        open(path, 'w').write(src.replace(original, broken))
    subprocess.run('rm -rf compat/build/test-results/oracleTest', shell=True)
    try:
        args = ['./gradlew', '-q', '--continue', ':compat:oracleTest']
        for fixture in FIXTURES:
            args += ['--tests', '*.' + fixture]
        r = subprocess.run(args, capture_output=True, text=True)
    finally:
        if src is not None:
            open(path, 'w').write(src)
    found = results()
    failed = [f for f in FIXTURES if found.get(f, (0, []))[1]]
    missing = [f for f in FIXTURES if f not in found]
    if name == 'clean':
        verdict = 'OK' if r.returncode == 0 and not failed and not missing else 'BROKEN BASELINE'
    else:
        verdict = 'CAUGHT' if failed else 'MISSED'
    print(f'{verdict:8} {name}: fails {", ".join(failed) or "nothing"}' + (f' (no results: {", ".join(missing)})' if missing else ''), flush=True)
    for fixture in failed:
        tests, fails = found[fixture]
        print(f'           {fixture}: {len(fails)} of {tests} failed; first: {fails[0]}', flush=True)
    if r.returncode != 0 and not found:
        print('           ' + ' / '.join(l.strip() for l in (r.stdout + r.stderr).splitlines() if l.strip())[-600:], flush=True)
