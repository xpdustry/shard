"""W3.12's one-line breaks: each objective kind group, each marker kind, the objectives JSON's field order, the markers'
UBJSON and localeprint, each applied alone to a clean checkout and run against the three objective fixtures. A break
must fail at least one of them. A clean run comes first, and must pass all three.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w3.12-objectives.py');
the results, as committed, are in w3.12-objectives.log.
"""
import glob, html, os, re, subprocess, sys

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

OBJ = 'sim/src/main/java/com/xpdustry/shard/sim/objectives/MapObjective.java'
OBJS = 'sim/src/main/java/com/xpdustry/shard/sim/objectives/MapObjectives.java'
MARK = 'sim/src/main/java/com/xpdustry/shard/sim/objectives/ObjectiveMarker.java'
LOC = 'sim/src/main/java/com/xpdustry/shard/sim/objectives/MapLocales.java'
JSON = 'compat/src/main/java/com/xpdustry/shard/compat/v160/rules/ObjectivesJson.java'
UBJ = 'compat/src/main/java/com/xpdustry/shard/compat/v160/rules/UbJson.java'

FIXTURES = ['ObjectiveOracleTest', 'ObjectivesJsonOracleTest', 'ObjectiveMapsOracleTest']
I = ' ' * 20  # a switch case's indent

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    # objective kinds, by group
    ('research/produce: alwaysUnlocked negated', OBJ,
     '&& def.fields().b("alwaysUnlocked");', '&& !def.fields().b("alwaysUnlocked");'),
    ('item/core item: core items need one more', OBJ,
     'coreItemCount.getOrDefault(item, 0) >= amount;', 'coreItemCount.getOrDefault(item, 0) > amount;'),
    ('build/unit count: one more block placed', OBJ,
     'placedBlockCount.getOrDefault(block, 0) >= count;', 'placedBlockCount.getOrDefault(block, 0) > count;'),
    ('destroy units/blocks/core: one more kill', OBJ,
     'enemyUnitsDestroyed >= count;', 'enemyUnitsDestroyed > count;'),
    ('timer: one frame late', OBJ,
     'return (countup += 1f) >= duration', 'return (countup += 1f) > duration'),
    ('flag/command mode: any flag will do', OBJ,
     'return rules.objectiveFlags.contains(flag);', 'return !rules.objectiveFlags.isEmpty();'),
    ('dependencies: parents ignored', OBJ,
     'if(!all.get(parent).completed) return false;', 'if(false) return false;'),
    ('completion: flagsRemoved ignored', OBJS,
     'rules.objectiveFlags.removeAll(objective.flagsRemoved);', '// rules.objectiveFlags.removeAll(objective.flagsRemoved);'),
    # marker kinds, each in its own control()
    ('marker shapeText: radius halved', MARK,
     'FLAG_BACKGROUND, !Mathf.equal((float)p1, 0f));\n' + I + 'case radius -> radius = (float)p1;',
     'FLAG_BACKGROUND, !Mathf.equal((float)p1, 0f));\n' + I + 'case radius -> radius = (float)p1 / 2f;'),
    ('marker point: radius halved', MARK,
     'case radius -> radius = (float)p1;\n' + I + 'case stroke -> stroke = (float)p1;\n' + I + 'case color',
     'case radius -> radius = (float)p1 / 2f;\n' + I + 'case stroke -> stroke = (float)p1;\n' + I + 'case color'),
    ('marker shape: arc end from p1', MARK,
     'case arc -> endAngle = (float)p2;', 'case arc -> endAngle = (float)p1;'),
    ('marker text: font size floored', MARK,
     'case fontSize -> fontSize = (float)p1;\n' + I + 'case textAlign',
     'case fontSize -> fontSize = (float)Math.floor(p1);\n' + I + 'case textAlign'),
    ('marker line: endPos x in tiles', MARK,
     'case endPos -> endPos.x = (float)p1 * TILE;', 'case endPos -> endPos.x = (float)p1;'),
    ('marker texture: width in tiles', MARK,
     'case textureSize -> width = (float)p1 * TILE;', 'case textureSize -> width = (float)p1;'),
    ('marker quad: pos x in tiles', MARK,
     'case pos -> vertices[0] = (float)p1 * TILE;', 'case pos -> vertices[0] = (float)p1;'),
    ('marker light: radius halved', MARK,
     'case radius -> radius = (float)p1;\n' + I + 'case color -> color = fromDouble(p1);\n' + I + 'default',
     'case radius -> radius = (float)p1 / 2f;\n' + I + 'case color -> color = fromDouble(p1);\n' + I + 'default'),
    # the codecs and localeprint
    ('objectives JSON: completed written first', JSON,
     'fields.add(COMPLETED);', 'fields.add(0, COMPLETED);'),
    ('markers UBJSON: an int typed as a long', UBJ,
     "case Integer i -> {\n" + I + "out.write('l');", "case Integer i -> {\n" + I + "out.write('L');"),
    ('localeprint: present keys read as missing', LOC,
     '|| !bundle.containsKey(key)) return', '|| bundle.containsKey(key)) return'),
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
