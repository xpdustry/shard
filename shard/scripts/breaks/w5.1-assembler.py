"""W5.1's one-line breaks: the unit assembler, its module, PayloadSeq, AssemblerAI, their codecs and Calls and the
placement rules, each applied alone to a clean checkout and run against the assembler fixtures. A break must fail at
least one of them. A clean run comes first, and must pass.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w5.1-assembler.py'): the
shared runner (runner.py) runs the breaks several at a time; -j 1 runs them one by one. The results, as committed,
are in w5.1-assembler.log.
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

SIM = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/units/'
CODEC = 'compat/src/main/java/com/xpdustry/shard/compat/v160/blocks/units/'
ASSEMBLER = SIM + 'UnitAssembler.java'
MODULE = SIM + 'UnitAssemblerModule.java'
AI = 'sim/src/main/java/com/xpdustry/shard/sim/ai/AssemblerAi.java'

FIXTURES = ['AssemblerOracleTest', 'AssemblerPlacementOracleTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    # drones
    ('assembler: drones built twice as fast', ASSEMBLER,
     'delta() * unitBuildSpeed() * powerStatus / p.droneConstructTime', 'delta() * unitBuildSpeed() * powerStatus * 2f / p.droneConstructTime'),
    ('assembler: a new drone faces 0', ASSEMBLER, 'unit.rotation = 90f;', 'unit.rotation = 0f;'),
    ('assembler: drones placed 5 degrees off', ASSEMBLER,
     'ai.targetPos.trns(i * 90f + 45f,', 'ai.targetPos.trns(i * 90f + 40f,'),
    ('assembler: drones face the area sideways', ASSEMBLER,
     'ai.targetAngle = i * 90f + 45f + 180f;', 'ai.targetAngle = i * 90f + 45f + 90f;'),
    ('assembler: loaded drones not found', ASSEMBLER,
     'if(groups != null && groups.byId(readUnits.get(i)) instanceof Unit unit) units.add(unit);', 'if(groups == null) units.add(null);'),
    # building
    ('assembler: builds at full rate without drones', ASSEMBLER, 'eff / plan.time', '1f / plan.time'),
    ('assembler: powerWarmup lerps from itself', ASSEMBLER,
     'Mathf.lerpDelta(powerStatus, powerStatus > 0.0001f', 'Mathf.lerpDelta(powerWarmup, powerStatus > 0.0001f'),
    ('assembler: a new tier keeps the progress', ASSEMBLER,
     'if(lastTier >= 0f){\n                progress = 0f;', 'if(lastTier >= 0f){\n                progress = progress;'),
    ('assembler: payloads thrown into a held area', ASSEMBLER, 'if(moveInPayload() && !wasOccupied){', 'if(moveInPayload()){'),
    ('assembler: takes one payload more', ASSEMBLER,
     'blocks.get(b.type(), b.id()) < Mathf.round(b.amount() * unitCost()) - already', 'blocks.get(b.type(), b.id()) <= Mathf.round(b.amount() * unitCost()) - already'),
    ('assembler: consumes past the cap', ASSEMBLER,
     'return enabled && !wasOccupied && Units.canCreate(world(), team, plan().unit) &&', 'return enabled && !wasOccupied &&'),
    # the area
    ('assembler: ground units don\'t hold the area', ASSEMBLER,
     '|| (!out.flying() && u.isGrounded())));', '|| false));'),
    ('assembler: buildings don\'t hold the area', ASSEMBLER,
     'if(!out.flying() && Collisions.overlapsTile(', 'if(false && Collisions.overlapsTile('),
    ('assembler: the unit check box 1.2 hit sizes', ASSEMBLER, 'float hsize = hitSize * 1.4f;', 'float hsize = hitSize * 1.2f;'),
    ('assembler: units of the output type count when same', ASSEMBLER,
     'u -> (!same || u.type() != output) && !u.spawnedByCore', 'u -> !u.spawnedByCore'),
    # the unit made
    ('spawned: no position jitter', ASSEMBLER, 'float sx = spawn.x + Mathf.range(rand, 0.001f);', 'float sx = spawn.x;'),
    ('spawned: the unit faces a quarter turn off', ASSEMBLER, 'unit.rotation = rotdeg();', 'unit.rotation = rotdeg() + 90f;'),
    ('spawned: no rally point', ASSEMBLER,
     'if(unit.controller instanceof CommandAi ai && commandPos != null){', 'if(unit.controller instanceof CommandAi ai && commandPos == null){'),
    ('spawned: no sound pitch drawn', ASSEMBLER, '        Mathf.range(rand, 0.06f);\n', '\n'),
    ('yeet: no sound pitch drawn', ASSEMBLER, '        Mathf.range(world().rand(), 0.1f);\n', '\n'),
    ('spawned: payloads kept', ASSEMBLER, '        blocks.clear();\n', '\n'),
    # the module
    ('module: throws into a held area', MODULE, '&& !link.wasOccupied &&', '&&'),
    ('module: never raises the tier', MODULE, '            link.updateModules(this);\n', '\n'),
    ('module: fits a tile further out', ASSEMBLER,
     'Footprint.TILE_SIZE * params().areaSize / 2f - Footprint.TILE_SIZE / 2f);', 'Footprint.TILE_SIZE * params().areaSize / 2f + Footprint.TILE_SIZE / 2f);'),
    # placement
    ('placement: touching edges allowed below', ASSEMBLER, 'area[1] -= 0.1f / 2f;', 'area[1] -= 0f;'),
    ('placement: construct sites ignored', ASSEMBLER,
     'if(current.isA(KindKey.of("unit-assembler")) && overlaps(', 'if(false && overlaps('),
    # AssemblerAI
    ('ai: faces its angle from 10 units', AI, 'unit.within(targetPos.x, targetPos.y, 5f)', 'unit.within(targetPos.x, targetPos.y, 10f)'),
    ('ai: in position within 30 degrees', AI, 'Angles.within(unit.rotation, targetAngle, 15f)', 'Angles.within(unit.rotation, targetAngle, 30f)'),
    ('ai: smoother approach', AI, 'moveTo(targetPos.x, targetPos.y, 1f, 3f);', 'moveTo(targetPos.x, targetPos.y, 1f, 6f);'),
    # PayloadSeq, codecs and Calls
    ('codec: payloads saved last first', CODEC + 'UnitAssemblerCodec.java',
     'for(int i = 0; i < seq.entries(); i++){', 'for(int i = seq.entries() - 1; i >= 0; i--){'),
    ('codec: drone ids off by one', CODEC + 'UnitAssemblerCodec.java',
     'for(Unit unit : assembler.units) out.i(unit.id());', 'for(Unit unit : assembler.units) out.i(unit.id() + 1);'),
    ('codec: payload amounts off by one', CODEC + 'UnitAssemblerCodec.java', 'out.i(seq.amount(i));', 'out.i(seq.amount(i) + 1);'),
    ('codec: the rally point not read', CODEC + 'UnitAssemblerCodec.java',
     'if(revision >= 1) assembler.commandPos = UnitBlockBodies.readVec(in);', 'if(revision >= 1) UnitBlockBodies.readVec(in);'),
    ('Call: the drone id off by one', CODEC + 'AssemblerCallMapping.java',
     'new Packets.AssemblerDroneSpawned(pos, spawned.unit())', 'new Packets.AssemblerDroneSpawned(pos, spawned.unit() + 1)'),
]

runner.run(BREAKS, FIXTURES, [':compat:oracleTest'], ['compat/build/test-results/oracleTest'])
