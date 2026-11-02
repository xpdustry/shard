"""W4.2's one-line breaks: each command and stance family of CommandAI (WP72), the formation, the order batches, and the
save's attack target, each applied alone to a clean checkout and run against the command fixtures
(compat's v160.command oracle tests). A break must fail at least one of them. A clean run comes first, and must pass.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.2-commands.py');
the results, as committed, are in w4.2-commands.log.
"""
import glob, html, os, re, shutil, subprocess, sys

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

AI = 'sim/src/main/java/com/xpdustry/shard/sim/ai/CommandAi.java'
CMD = 'sim/src/main/java/com/xpdustry/shard/sim/ai/command/UnitCommand.java'
STANCES = 'sim/src/main/java/com/xpdustry/shard/sim/ai/command/UnitStances.java'
GROUP = 'sim/src/main/java/com/xpdustry/shard/sim/ai/command/UnitGroup.java'
BATCHES = 'sim/src/main/java/com/xpdustry/shard/sim/ai/command/CommandBatches.java'
ORDERS = 'sim/src/main/java/com/xpdustry/shard/sim/ai/command/Orders.java'
WIRE = 'compat/src/main/java/com/xpdustry/shard/compat/v160/entity/WireControllers.java'

FIXTURES = ['MoveOracleTest', 'PayloadCommandsOracleTest', 'StancesOracleTest', 'SupportCommandsOracleTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    # move: the formation and the path
    ('move: no formation offset', AI,
     'offsetDestination.add(group.positions[groupIndex * 2], group.positions[groupIndex * 2 + 1]);', 'offsetDestination.add(0f, 0f);'),
    ('move: formation squeezes less', GROUP,
     '.lerp(v2.set(0f, 0f), 0.3f);', '.lerp(v2.set(0f, 0f), 0.31f);'),
    ('move: the path goes to the plain destination', AI,
     'paths.pathPosition(unit, offsetDestination.x, offsetDestination.y, currentDestination.x, currentDestination.y)',
     'paths.pathPosition(unit, offsetDestination.x, offsetDestination.y, offsetDestination.x, offsetDestination.y)'),
    ('move: the final point is exact', AI,
     'isFinalPoint &= offsetDestination.epsilonEquals(moveTarget.x, moveTarget.y, 4.1f);',
     'isFinalPoint &= offsetDestination.epsilonEquals(moveTarget.x, moveTarget.y, 0.1f);'),
    ('batches: every call its own batch', BATCHES,
     'for(Batch batch : batches) if(batch.at(x, y)) return batch;', 'for(Batch batch : batches) if(false) return batch;'),
    # the payload commands
    ('loadUnits: a shorter reach', AI,
     'unit.type().hitSize() * 2f,', 'unit.type().hitSize() * 0.1f,'),
    ('unloadPayload: no auto-drop', AI,
     'if(current == UnitCommand.UNLOAD_PAYLOAD && Payloads.hasPayload(unit)) Payloads.payloadDropped(unit, unit.x, unit.y, cause);',
     'if(false) Payloads.payloadDropped(unit, unit.x, unit.y, cause);'),
    ('loadBlocks: no exact arrival', CMD,
     'LOAD_BLOCKS("loadBlocks", null, false, false, false, true),', 'LOAD_BLOCKS("loadBlocks", null, false, false, false, false),'),
    ('loopPayload: a shorter cooldown', AI,
     'if(Payloads.hasPayload(unit)) return;\n                payloadPickupCooldown = 60f;',
     'if(Payloads.hasPayload(unit)) return;\n                payloadPickupCooldown = 30f;'),
    ('enterPayload: no snap to the building', CMD,
     'ENTER_PAYLOAD("enterPayload", null, false, false, true, false),', 'ENTER_PAYLOAD("enterPayload", null, false, false, false, false),'),
    # the commands with an AI of their own
    ('repair/rebuild/assist/mine: stances kept across a switch', ORDERS,
     'if(ai.hasStance(stance) && !allowed.contains(stance)) ai.disableStance(stance);', 'if(false) ai.disableStance(stance);'),
    ('mine: no mineauto', AI,
     'setStance(table.mineAuto);\n        }', '// setStance(table.mineAuto);\n        }'),
    ('rebuild: patrol compatible', STANCES,
     'if(stance == patrol) return command != UnitCommand.REPAIR && command != UnitCommand.ASSIST && command != UnitCommand.REBUILD;',
     'if(stance == patrol) return true;'),
    ('unreachable: AI teams keep walking', AI,
     'if(UnitCalls.isAI(world, unit.team) && (unreachable ||', 'if(!UnitCalls.isAI(world, unit.team) && (unreachable ||'),
    # stances
    ('holdfire: ignored', AI,
     'return !hasStance(UnitStances.of(unit.world().content()).holdFire);', 'return true;'),
    ('pursuetarget: no pursuit', AI,
     'if(hasStance(table.pursueTarget) && !hasStance(table.patrol) && target != null && attackTarget == null && targetPos == null){',
     'if(false){'),
    ('patrol: the finished point not requeued', AI,
     'if(prev != null && (hasStance(UnitStances.of(world.content()).patrol) || current == UnitCommand.LOOP_PAYLOAD)){',
     'if(prev != null && (current == UnitCommand.LOOP_PAYLOAD)){'),
    ('ram: ignored', AI,
     'boolean ramming = hasStance(table.ram);', 'boolean ramming = false;'),
    ('boost: only for enterPayload', AI,
     'return hasStance(table.boost) || command() == UnitCommand.ENTER_PAYLOAD;', 'return command() == UnitCommand.ENTER_PAYLOAD;'),
    ('stop: orders kept', ORDERS,
     '// not a real stance, just cancels orders\n                ai.clearCommands();', '// not a real stance, just cancels orders'),
    ('holdposition: never allowed', STANCES,
     'if(current != null && current.holdPosition() && holdPosition != -1) out.add(holdPosition);', ''),
    ('item stances: mineauto kept', STANCES,
     'incompatible[i].set(mineAuto);', ''),
    # the save
    ('save: a unit attack target not looked up again', WIRE,
     'ai.readAttackTarget = command.attack() != null && command.attack().kind() != 1 ? command.attack().ref() : -1;',
     'ai.readAttackTarget = -1;'),
]

RESULTS = 'compat/build/test-results/oracleTest'

def results():
    out = {}
    for x in glob.glob(RESULTS + '/TEST-*.xml'):
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
    shutil.rmtree(RESULTS, ignore_errors=True)
    try:
        args = ['./gradlew', '-q', '--continue', ':compat:oracleTest']
        for fixture in FIXTURES:
            args += ['--tests', '*.v160.command.' + fixture]
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
