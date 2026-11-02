"""W4.4's one-line breaks: one or more per new payload kind (constructor, deconstructor, payload mass driver, loader,
unloader, source, void), their codecs, the payload costs and the nested payload load, each applied alone to a clean
checkout and run against the payload block fixtures. A break must fail at least one of them. A clean run comes first,
and must pass.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.4-payload-blocks.py'): the
shared runner (runner.py) runs the breaks several at a time; -j 1 runs them one by one. The results, as committed,
are in w4.4-payload-blocks.log.
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

SIM = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/payload/'
CODEC = 'compat/src/main/java/com/xpdustry/shard/compat/v160/blocks/payload/'
PRODUCER = SIM + 'BlockProducerBuild.java'
CONSTRUCTOR = SIM + 'Constructor.java'
DECON = SIM + 'PayloadDeconstructor.java'
DRIVER = SIM + 'PayloadMassDriver.java'
LOADER = SIM + 'PayloadLoaderBuild.java'
POWER = SIM + 'PayloadLoader.java'
UNLOADER = SIM + 'PayloadUnloader.java'
SOURCE = SIM + 'PayloadSource.java'
VOID = SIM + 'PayloadVoid.java'
COSTS = SIM + 'PayloadCosts.java'
SAVED = 'compat/src/main/java/com/xpdustry/shard/compat/v160/msav/SavedUnits.java'
BODIES = CODEC + 'PayloadBodies.java'

FIXTURES = ['PayloadBlocksOracleTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    ('constructor: buildSpeed ignored', PRODUCER,
     'progress += buildSpeed() * edelta();', 'progress += edelta();'),
    ('constructor: one more of each requirement taken', PRODUCER,
     'Mathf.ceil(info.amounts[i] * multiplier);', 'Mathf.ceil(info.amounts[i] * multiplier) + 1;'),
    ('constructor: a new recipe keeps the progress', CONSTRUCTOR,
     'if(tile.recipe != block) tile.progress = 0f;', 'if(false) tile.progress = 0f;'),
    ('deconstructor: half again as fast', DECON,
     'edelta() * deconstructSpeed / PayloadCosts', 'edelta() * deconstructSpeed * 1.5f / PayloadCosts'),
    ('deconstructor: dumps only past 30 items', DECON,
     'if(items.total() > 0){', 'if(items.total() > 30){'),
    ('deconstructor: finishes a little late', DECON,
     'if(progress >= 1f){', 'if(progress >= 1.5f){'),
    ('deconstructor codec: accum written off by one', CODEC + 'PayloadDeconstructorCodec.java',
     'for(float v : deconstructor.accum) out.f(v);', 'for(float v : deconstructor.accum) out.f(v + 1f);'),
    ('mass driver: fires a tick early', DRIVER,
     'if(charge >= params.chargeTime()){', 'if(charge >= params.chargeTime() - 1f){'),
    ('mass driver: arrival sound draws nothing', DRIVER,
     'Mathf.range(world().rand(), 0.2f);', '// Mathf.range(world().rand(), 0.2f);'),
    ('mass driver: shooters linked anywhere stay queued', DRIVER,
     '&& entity.block() == block() && entity.link == pos() &&', '&& entity.block() == block() &&'),
    ('mass driver codec: charging saved as false', CODEC + 'PayloadMassDriverCodec.java',
     'out.bool(driver.charging);', 'out.bool(false);'),
    ('loader: one item per load', LOADER,
     'for(int j = 0; j < params.itemsLoaded() && items.any(); j++){', 'for(int j = 0; j < 1 && items.any(); j++){'),
    # a refused item only matters for blocks without separateItemCapacity; a container exports through this clause
    ('loader: a full stack exports one item late', LOADER,
     'if(build.items.get(i) >= info.itemCapacity && items.has(i)) return true;',
     'if(build.items.get(i) >= info.itemCapacity + 1 && items.has(i)) return true;'),
    ('loader: no base power use', POWER,
     ': params.basePowerUse();', ': 0f;'),
    ('unloader: offloads once a tick', UNLOADER,
     'for(int i = 0; i < params.offloadSpeed(); i++){', 'for(int i = 0; i < 1; i++){'),
    ('unloader: exports with items left', UNLOADER,
     '(!build.block().hasItems() || build.items.empty())', '(!build.block().hasItems() || build.items.total() < 5)'),
    ('source: a new payload starts off centre', SOURCE,
     'payVector.setZero();', 'payVector.set(2f, 0f);'),
    ('source codec: no rally point saved as zeros', CODEC + 'PayloadSourceCodec.java',
     'out.f(source.commandPos == null ? Float.NaN : source.commandPos.x);', 'out.f(source.commandPos == null ? 0f : source.commandPos.x);'),
    ('void: turns what it takes', VOID,
     'if(moveInPayload(false) && efficiency > 0){', 'if(moveInPayload(true) && efficiency > 0){'),
    ('costs: a unit build time from zero', COSTS,
     'float buildTime = -1f;', 'float buildTime = 0f;'),
    ('nested load: a carried building keeps a stale id', SAVED,
     'building.id(next[0]++);\n        if(world.buildings().info(building.block()).drawsField) world.rand().nextLong();',
     'building.id(next[0]);\n        if(world.buildings().info(building.block()).drawsField) world.rand().nextLong();'),
    ('nested load: nested payloads not measured', BODIES,
     'if(in.context() != null){', 'if(false){'),
]

runner.run(BREAKS, FIXTURES, [':compat:oracleTest'], ['compat/build/test-results/oracleTest'])
