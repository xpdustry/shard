"""W4.1's one-line breaks: the unit factory, the reconstructor, their shared base, codecs and Call, the unit cap and the
payload quirk they rely on, each applied alone to a clean checkout and run against UnitBlockOracleTest's three
fixtures (units built at 27.5 times, so the reconstructor's leftover progress is fractional). A break must fail at
least one of them. A clean run comes first, and must pass all three.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.1-unit-blocks.py'): the
shared runner (runner.py) runs the breaks several at a time; -j 1 runs them one by one. --no-build-cache makes every
run execute the fixtures. The results, as committed, are in w4.1-unit-blocks.log: the clean run and all 23 breaks in
one run on one head (review 43; W4.10).
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

UNITS = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/units/'
FAC = UNITS + 'UnitFactory.java'
REC = UNITS + 'Reconstructor.java'
BASE = UNITS + 'UnitBuild.java'
CODECS = 'compat/src/main/java/com/xpdustry/shard/compat/v160/blocks/units/'
CAP = 'sim/src/main/java/com/xpdustry/shard/sim/unit/Units.java'
PAY = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/payload/PayloadBuild.java'

FIXTURES = ['UnitBlockOracleTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    # the factory
    ('factory: progress ignores unitBuildSpeed', FAC,
     'progress += edelta() * unitBuildSpeed();', 'progress += edelta();'),
    ('factory: items not scaled by unitCost', FAC,
     'public float consumeMultiplier(Consume consume){\n        return unitCost();', 'public float consumeMultiplier(Consume consume){\n        return 1f;'),
    ('factory: one more item accepted', FAC,
     'return maximumAccepted(params().capacities, item);', 'return maximumAccepted(params().capacities, item) + 1;'),
    ('factory: consumes while holding a unit', FAC,
     'return enabled && payload == null && activateUnitFactories();', 'return enabled && activateUnitFactories();'),
    ('factory: a new one starts on its last plan', FAC,
     '                    currentPlan = i;\n                    break;', '                    currentPlan = plans.length - 1;\n                    break;'),
    ('factory: a unit type config picks another plan', FAC,
     'if(p.plans[i].unit == type){', 'if(p.plans[i].unit != type){'),
    ('factory: null leaves the command', FAC,
     '.clear(factory -> factory.command = -1);', '.clear(factory -> {});'),
    ('factory: speedScl eases faster', FAC,
     'speedScl = Mathf.lerpDelta(speedScl, 1f, 0.05f);', 'speedScl = Mathf.lerpDelta(speedScl, 1f, 0.06f);'),
    # the reconstructor
    ('reconstructor: progress ignores unitBuildSpeed', REC,
     'progress += edelta() * unitBuildSpeed();', 'progress += edelta();'),
    ('reconstructor: items not scaled by unitCost', REC,
     'public float consumeMultiplier(Consume consume){\n        return unitCost();', 'public float consumeMultiplier(Consume consume){\n        return 1f;'),
    ('reconstructor: constructing read live, not cached', REC,
     'return constructing && enabled && activateUnitFactories();', 'return isConstructing() && enabled && activateUnitFactories();'),
    ('reconstructor: leftover progress dropped', REC,
     '                    progress %= 1f;', '                    progress = 0f;'),
    ('reconstructor: speedScl eases faster', REC,
     'speedScl = Mathf.lerpDelta(speedScl, valid ? 1f : 0f, 0.05f);', 'speedScl = Mathf.lerpDelta(speedScl, valid ? 1f : 0f, 0.06f);'),
    # the shared base
    ('base: no sound pitch draw', BASE,
     '        Mathf.range(world().rand(), 0.06f);', '        // Mathf.range(world().rand(), 0.06f);'),
    ('base: unitBlockSpawn keeps the progress', BASE,
     '        progress = 0f;\n        payload = null;', '        payload = null;'),
    ('base: no unitBlockSpawn Call', BASE,
     'world().publish(new UnitBlockEvent.Spawned(tile()));', '// world().publish(new UnitBlockEvent.Spawned(tile()));'),
    ('base: made units ignore the rally point', BASE,
     'if(commandPos != null) ai.commandPosition(commandPos);', '// if(commandPos != null) ai.commandPosition(commandPos);'),
    # codecs and the Call
    ('codec: no command written as 254', CODECS + 'UnitBlockBodies.java',
     'out.b(command < 0 ? 255 : command);', 'out.b(command < 0 ? 254 : command);'),
    ('codec: factory plan as a byte', CODECS + 'UnitFactoryCodec.java',
     'out.s(factory.currentPlan);', 'out.b(factory.currentPlan);'),
    ('codec: reconstructor progress not read', CODECS + 'ReconstructorCodec.java',
     'if(revision >= 1) reconstructor.progress = in.f();', 'if(revision >= 1) in.f();'),
    ('Call: unitBlockSpawn tile transposed', CODECS + 'UnitBlockCallMapping.java',
     'Point2.pack(tiles.x(spawned.tile()), tiles.y(spawned.tile()))', 'Point2.pack(tiles.y(spawned.tile()), tiles.x(spawned.tile()))'),
    # the cap and the payload quirk
    ('cap: one unit over', CAP,
     'return count < getCap(world, team) &&', 'return count <= getCap(world, team) &&'),
    ('payload: a push no longer delays the dump', PAY,
     'if(pushed != null) dest = pushed;', '// if(pushed != null) dest = pushed;'),
]

runner.run(BREAKS, FIXTURES, [':compat:oracleTest', '--no-build-cache'], ['compat/build/test-results/oracleTest'])
