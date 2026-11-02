"""W5.2's one-line breaks: the RTS AI's squad, defence and target rules, the base builder's path, placement and plan
rules, the base registry and schematic rotation, and the hooks they read, each applied alone to a clean checkout and
run against the team-AI fixtures (TeamAiOracleTest, BaseRegistryOracleTest); the shared hitSize fix also against
its :sim test (HitSizeTest). A break must fail at least one of them.
A clean run comes first, and must pass.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w5.2-team-ai.py'): the shared
runner (runner.py) runs the breaks several at a time. The results, as committed, are in w5.2-team-ai.log.
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

TEAM = 'sim/src/main/java/com/xpdustry/shard/sim/ai/team/'
RTS = TEAM + 'RtsAi.java'
BUILD = TEAM + 'BaseBuilderAi.java'
REGISTRY = TEAM + 'BaseRegistry.java'
ROTATION = TEAM + 'SchematicRotation.java'
COLLIDER = 'sim/src/main/java/com/xpdustry/shard/sim/bullet/BulletCollider.java'
TURRET = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/turret/TurretBuild.java'
PARTS = 'content/src/main/java/com/xpdustry/shard/content/base/BaseParts.java'
BUILDING = 'sim/src/main/java/com/xpdustry/shard/sim/building/Building.java'

FIXTURES = ['TeamAiOracleTest', 'BaseRegistryOracleTest']

# the shared hitSize fix: a dead building measures its tile's block (BuildingComp.java:2054); it must fail both its
# unit test and the RTS fixture, so it is in both runs
HIT_SIZE = ('hitSize: a dead building keeps its size', BUILDING,
            'if(world().buildings().at(tile) == this) return block.size()', 'if(true) return block.size()')

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    # RTS: squads
    ('squad: a smaller radius', RTS, 'static final float SQUAD_RADIUS = 60f;', 'static final float SQUAD_RADIUS = 30f;'),
    ('squad: mobilized and idle units mix', RTS, '((u.flag == 0) == (unit.flag == 0)) && ', ''),
    ('squad: attacking units lead squads', RTS, '!cai.hasCommand() && !cai.isAttacking()', '!cai.hasCommand()'),
    ('squad: rounds every second', RTS, 'timer(timers, world, TIMER_UPDATE, 60f * 2f)', 'timer(timers, world, TIMER_UPDATE, 60f)'),
    ('squad: no start offset', RTS,
     'timers[TIMER_UPDATE] = world.timers().time() - Mathf.random(world.rand(), 60f * 2f);', 'timers[TIMER_UPDATE] = world.timers().time();'),
    # RTS: defence
    ('defend: cores not first', RTS, 'float value = b instanceof Core ? -999999f : b.dst(ax, ay);', 'float value = b.dst(ax, ay);'),
    ('defend: the first of equals', RTS, 'if(value <= min){', 'if(value < min){'),
    ('defend: a shorter aggressor search', RTS, 'float checkRange = 350f;', 'float checkRange = 100f;'),
    ('defend: at the aggressor itself', RTS, 'ai.commandPosition(defendPos, true);', 'ai.commandPosition(defendPos, false);'),
    ('defend: defenders not mobilized', RTS, 'if(!defendingCore) unit.flag = 1;', ''),
    ('defend: no damage heard', COLLIDER, 'TeamAis.buildDamaged(building);', ''),
    # RTS: targets
    ('target: no minimum squad', RTS, 'if(total < teamRule.rtsMinSquad) return null;', 'if(total < 1) return null;'),
    ('target: cores first', RTS,
     '{BlockFlag.generator, BlockFlag.factory, BlockFlag.core, BlockFlag.battery, BlockFlag.drill}',
     '{BlockFlag.core, BlockFlag.generator, BlockFlag.factory, BlockFlag.battery, BlockFlag.drill}'),
    ('target: no shuffle', RTS, 'int ii = Mathf.random(rand, i);', 'int ii = i;'),
    ('target: fewer checked', RTS, 'MAX_TARGETS_CHECKED = 15;', 'MAX_TARGETS_CHECKED = 3;'),
    ('target: distance weighs more', RTS, '+ a.dst(x, y) / 10000f, (1f - weightB) + b.dst(x, y) / 10000f', '+ a.dst(x, y) / 100f, (1f - weightB) + b.dst(x, y) / 100f'),
    ('target: one squad per target ignored', RTS, 'round.assignedTargets.contains(b.id()) || ', ''),
    ('target: no weight threshold', RTS, '(weight < teamRule.rtsMinWeight && total < teamRule.rtsMaxSquad)', '(weight < 0f && total < teamRule.rtsMaxSquad)'),
    ('estimate: no extra radius', RTS, 'float extraRadius = 50f;', 'float extraRadius = 0f;'),
    ('estimate: turrets do no damage', RTS, 'dps[0] += t.estimateDps();', ''),
    ('estimate: units do no damage', RTS, 'dps[0] += dpsEstimate(u.type());', ''),
    ('estimate: a turret fires twice as often', TURRET, 'type().shoot.shots / type().reload * 60f', 'type().shoot.shots / type().reload * 120f'),
    ('core units: every 6 s', RTS, 'timer(timers, world, TIMER_SPAWN, 60 * 7f)', 'timer(timers, world, TIMER_SPAWN, 60 * 6f)'),
    # base builder
    ('build: cores not filled', BUILD, 'core.items.set(item, core.getMaximumAccepted(item));', ';'),
    ('build: one core unit per core', BUILD, 'CORE_UNIT_MULTIPLIER = 2;', 'CORE_UNIT_MULTIPLIER = 1;'),
    ('build: shorter walk steps', BUILD, 'static final int PATH_STEP = 50;', 'static final int PATH_STEP = 10;'),
    ('build: path without neighbours', BUILD, 'calcPath.add(Point2.pack(p.x() + x, p.y() + y));', ''),
    ('build: parts on the path', BUILD, '&& covers(tiles, tile.block().size(), realX, realY, path)) return false;', '&& false) return false;'),
    ('build: placement interval reversed', BUILD,
     'Mathf.lerp(PLACE_INTERVAL_MIN, PLACE_INTERVAL_MAX,', 'Mathf.lerp(PLACE_INTERVAL_MAX, PLACE_INTERVAL_MIN,'),
    ('build: fewer attempts', BUILD, 'static final int ATTEMPTS = 6,', 'static final int ATTEMPTS = 3,'),
    ('build: empty parts more often', BUILD, 'EMPTY_CHANCE = 0.01f;', 'EMPTY_CHANCE = 0.1f;'),
    ('build: parts near spawns', BUILD, 'float range = 8f * 40f;', 'float range = 8f * 20f;'),
    ('build: drills on other ores', BUILD, 'if(anyDrills && (counts[1] != 0 || counts[0] == 0)) return false;', 'if(anyDrills && counts[0] == 0) return false;'),
    ('build: fewer turns', BUILD, 'int rotation = Mathf.range(rand, 2);', 'int rotation = Mathf.range(rand, 1);'),
    ('build: centre not turned', BUILD, '.rotateAround(axis, rotdeg);', '.rotateAround(axis, 0);'),
    ('build: plans first', BUILD, 'data.plans().add(new BlockPlan(', 'data.plans().addFirst(new BlockPlan('),
    # registry and rotation
    ('registry: tier power', REGISTRY, ', 1.4f);', ', 1.5f);'),
    ('registry: sandbox tiles kept', REGISTRY, 'if(tile.block().buildVisibility().equals("visibility:sandboxOnly")) continue;', ''),
    ('registry: no drill centre', REGISTRY, 'if(drills > 0){', 'if(false){'),
    ('registry: no fallback names', PARTS, 'content.block(FALLBACK.getOrDefault(blockName, blockName));', 'content.block(blockName);'),
    ('rotation: the other way', ROTATION, 'int direction = Mathf.sign(counter);', 'int direction = -Mathf.sign(counter);'),
    ('rotation: points not turned', ROTATION, 'return turn(point.x(), point.y(), direction);', 'return point;'),
    ('rotation: processor links not turned', ROTATION, 'return logicPointConfig(config, direction);', 'return config;'),
    HIT_SIZE,
]
SIM = [('clean', None, None, None), HIT_SIZE]

# one run per task, so each run's --tests filters match its task
runner.run(SIM, ['HitSizeTest'], [':sim:test'], ['sim/build/test-results/test'])
runner.run(BREAKS, FIXTURES, [':compat:oracleTest'], ['compat/build/test-results/oracleTest'])
