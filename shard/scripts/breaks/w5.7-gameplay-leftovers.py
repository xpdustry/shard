"""W5.7's one-line breaks: the block unit's pass-through (damage, death, team), the payload statements (payDrop,
payTake, payEnter, setprop @payloadType) and a unit's new id after it enters a block, the destroyed-building stats,
and the duct unloader's codec, each applied alone to a clean checkout and run against the fixtures. A break must fail
at least one of them. A clean run comes first, and must pass.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w5.7-gameplay-leftovers.py'):
the shared runner (runner.py) runs the breaks several at a time; -j 1 runs them one by one. The results, as committed,
are in w5.7-gameplay-leftovers.log.
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

SIM = 'sim/src/main/java/com/xpdustry/shard/sim/'
UNIT = SIM + 'unit/Unit.java'
PLAYER = SIM + 'unit/Player.java'
PAYLOADS = SIM + 'unit/Payloads.java'
STATEMENTS = SIM + 'logic/UnitStatements.java'
HOST = SIM + 'logic/WorldHost.java'
PAYLOAD_BUILD = SIM + 'blocks/payload/PayloadBuild.java'
CONVEYOR = SIM + 'blocks/payload/PayloadConveyorBuild.java'
STATE = SIM + 'objectives/ObjectiveState.java'
DUCT_CODEC = 'compat/src/main/java/com/xpdustry/shard/compat/v160/blocks/distribution/DirectionalUnloaderCodec.java'

FIXTURES = ['BlockUnitOracleTest', 'UnitLogicOracleTest', 'ObjectiveOracleTest', 'DuctOracleTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    ('block unit: damage reaches the turret halved', UNIT,
     'blockTile.damage(amount);', 'blockTile.damage(amount * 0.5f);'),
    ('block unit: killed() spares the turret', UNIT,
     'if(blockTile != null) blockTile.kill();', 'if(blockTile == null) blockTile = null;'),
    ('block unit: a new team stays on the unit', UNIT,
     'if(blockTile.team != team) world().buildings().changeTeam', 'if(false) world().buildings().changeTeam'),
    ('player: the update sets the unit\'s team field', PLAYER,
     '            unit.team(team);\n            deathTimer = 0;', '            unit.team = team;\n            deathTimer = 0;'),
    ('payDrop: no timeout', STATEMENTS,
     'if(!units.timeoutDone(unit, LogicAi.TRANSFER_DELAY)) return;\n        if(unit.payloads != null && Payloads.hasPayload(unit)){',
     'if(unit.payloads != null && Payloads.hasPayload(unit)){'),
    ('payTake: units within twice the reach', STATEMENTS,
     'other.hitSize + unit.hitSize * 1.2f', 'other.hitSize + unit.hitSize * 2.4f'),
    ('payTake: a building is never lifted whole', STATEMENTS,
     'Payloads.pickedBuildPayload(unit, build, true, new Cause.Ai(unit.team));', '{}'),
    ('payTake: a take that found nothing keeps the timeout', STATEMENTS,
     '        units.updateTimeout(unit);\n    }\n\n    /** {@code payEnter}', '    }\n\n    /** {@code payEnter}'),
    ('payEnter: refused by the building under it', STATEMENTS,
     'if(build != null && unit.team == build.team && build.canControlSelect(unit)) Payloads',
     'if(build != null && unit.team == build.team && build.canControlSelect(unit) && false) Payloads'),
    ('entering a block: no new id posted', PAYLOADS,
     'if(counts != null) counts.postNewId(unit);', 'if(counts == null) counts.postNewId(unit);'),
    ('payload block: an entering unit keeps no rotation', PAYLOAD_BUILD,
     'payRotation = unit.rotation;', 'payRotation = 0f;'),
    ('payload conveyor: an entering unit is lost', CONVEYOR,
     'item = Payloads.handleUnitPayload(unit);', 'Payloads.handleUnitPayload(unit);'),
    ('setprop @payloadType: a unit made for the derelict', HOST,
     'Unit made = Unit.create(world, type, unit.team);', 'Unit made = Unit.create(world, type, 0);'),
    ('setprop @payloadType: a building made for the derelict', HOST,
     'world.buildings().createPayload(block, unit.team);', 'world.buildings().createPayload(block, 0);'),
    ('setprop @payloadType: null leaves the payload', HOST,
     'unit.payloads.pop();', '{}'),
    ('stats: a destroyed building counts twice', STATE,
     'stats.buildingsDestroyed++;', 'stats.buildingsDestroyed += 2;'),
    ('stats: derelict buildings don\'t count', STATE,
     'else GameStats.increment(stats.destroyedBlockCount', 'else if(building.team != 0) GameStats.increment(stats.destroyedBlockCount'),
    ('duct unloader codec: offset read off by one', DUCT_CODEC,
     'unloader.offset = in.s();', 'unloader.offset = in.s() + 1;'),
]

runner.run(BREAKS, FIXTURES, [':compat:oracleTest'], ['compat/build/test-results/oracleTest'])
