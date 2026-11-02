"""W4.7's one-line breaks: the cargo loader, the unload point, their codecs and Call, CargoAI, PrebuildAI and the
prebuild spawns, each applied alone to a clean checkout and run against CargoOracleTest. A break must fail it. Three clean
runs come first, and must pass: the fixture is the same every run (its wall-clock pins hold).

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.7-cargo.py');
the results, as committed, are in w4.7-cargo.log.
"""
import glob, html, os, re, shutil, subprocess, sys

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

UNITS = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/units/'
LOADER = UNITS + 'UnitCargoLoader.java'
POINT = UNITS + 'UnitCargoUnloadPoint.java'
CODECS = 'compat/src/main/java/com/xpdustry/shard/compat/v160/blocks/units/'
AI = 'sim/src/main/java/com/xpdustry/shard/sim/ai/'
CARGO = AI + 'CargoAi.java'
PREBUILD = AI + 'PrebuildAi.java'
SPAWNS = AI + 'PrebuildSpawns.java'

FIXTURES = ['CargoOracleTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    ('clean, again', None, None, None),
    ('clean, a third time', None, None, None),
    # the loader
    ('loader: builds its unit twice as fast', LOADER,
     'buildProgress += edelta() / p.unitBuildTime;', 'buildProgress += edelta() * 2f / p.unitBuildTime;'),
    ('loader: consumes with a unit too', LOADER,
     'return unit == null;\n    }\n\n    @Override\n    public void write', 'return true;\n    }\n\n    @Override\n    public void write'),
    ('loader: its unit faces 0', LOADER, 'unit.rotation = 90f;', 'unit.rotation = 0f;'),
    ('loader: takes items past its capacity', LOADER,
     'return items.total() < info().itemCapacity;', 'return items.total() <= info().itemCapacity;'),
    ('loader codec: saves no unit', CODECS + 'UnitCargoLoaderCodec.java',
     'out.i(loader.unit == null ? -1 : loader.unit.id());', 'out.i(-1);'),
    ('loader Call: the unit id off by one', CODECS + 'CargoCallMapping.java',
     'UnitTetherBlockSpawned(pos, spawned.unit())', 'UnitTetherBlockSpawned(pos, spawned.unit() + 1)'),
    # the unload point
    ('point: stale a tick late', POINT,
     '(staleTimer += 1f) >= staleTimeDuration()', '(staleTimer += 1f) > staleTimeDuration()'),
    ('point: takes a whole stack whatever it holds', POINT,
     'return Math.min(info().itemCapacity - items.total(), amount);', 'return Math.min(info().itemCapacity, amount);'),
    ('point: never dumps', POINT, 'if(dumpAccumulate(-1)){', 'if(false){'),
    ('point codec: stale saved negated', CODECS + 'UnitCargoUnloadPointCodec.java',
     'out.bool(point.stale);', 'out.bool(!point.stale);'),
    # CargoAI
    ('cargo: drops every second', CARGO, 'DROP_SPACING = 60f * 1.5f', 'DROP_SPACING = 60f'),
    ('cargo: next drop target from the same index', CARGO,
     'int index = (i + offset + 1) % targets.size();', 'int index = (i + offset) % targets.size();'),
    ('cargo: stale points taken first', CARGO,
     '                if(!target.stale){\n                    unloadTarget = target;\n                    break outer;',
     '                if(true){\n                    unloadTarget = target;\n                    break outer;'),
    ('cargo: least-held item first', CARGO,
     'build.items.get(ordered[j]) < amount', 'build.items.get(ordered[j]) > amount'),
    ('cargo: a reconfigured point kept', CARGO,
     'if(unloadTarget.item != itemTarget || unloadTarget.isPayload()){', 'if(unloadTarget.isPayload()){'),
    ('cargo: waits by the loader further off', CARGO, 'MOVE_RANGE = 6f', 'MOVE_RANGE = 12f'),
    # PrebuildAI and the spawns
    ('prebuild: production weighs like distribution', PREBUILD, 'case "production" -> 11f;', 'case "production" -> 10f;'),
    ('prebuild: stops nearer its plan', PREBUILD, 'moveTo(tx, ty, range - 10f, 20f);', 'moveTo(tx, ty, range - 30f, 20f);'),
    ('prebuild: circles the core wider', PREBUILD, 'circle(core.x(), core.y(), range / 1.8f);', 'circle(core.x(), core.y(), range / 1.5f);'),
    ('prebuild: no collecting for a plan it cannot pay', PREBUILD,
     '                        collectingItems = true;\n', '                        collectingItems = false;\n'),
    ('prebuild: stops nearer its ore', PREBUILD, 'moveTo(ox, oy, mine.range / 2f, 20f);', 'moveTo(ox, oy, mine.range / 3f, 20f);'),
    ('spawns: the core unit faces up', SPAWNS, 'core.x(), core.y(), 0f);', 'core.x(), core.y(), 90f);'),
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
caught = total = 0
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
    shutil.rmtree('compat/build/test-results/oracleTest', ignore_errors=True)
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
    if name.startswith('clean'):
        verdict = 'OK' if r.returncode == 0 and not failed and not missing else 'BROKEN BASELINE'
    else:
        total += 1
        verdict = 'CAUGHT' if failed else 'MISSED'
        caught += verdict == 'CAUGHT'
    print(f'{verdict:8} {name}: fails {", ".join(failed) or "nothing"}' + (f' (no results: {", ".join(missing)})' if missing else ''), flush=True)
    for fixture in failed:
        tests, fails = found[fixture]
        print(f'           {fixture}: {len(fails)} of {tests} failed; first: {fails[0]}', flush=True)
    if r.returncode != 0 and not found:
        print('           ' + ' / '.join(l.strip() for l in (r.stdout + r.stderr).splitlines() if l.strip())[-600:], flush=True)
print(f'{caught} of {total} breaks fail', flush=True)
