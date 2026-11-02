"""W4.10's one-line breaks: one per test review 43's gaps added, each applied alone to a clean checkout and run
against that test's class. A break must fail it. A clean run comes first, and must pass.

  - TurretRangeHashToggleTest: the turret stops hashing lastRangeChange;
  - FogOutcomeOracleTest (vanilla's fog unpinned): the dynamic circle loses vanilla's one extra tile;
  - FogSaveOracleTest (the save continuation): Shard reads each discovered run of the loaded static-fog-data chunk one tile short;
  - MoveOutcomeOracleTest (vanilla's RTS unpinned): a formation keeps its units twice as far apart.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.10-review43.py'): two
runner.run calls, the :sim tests and then the oracle ones, so each Gradle run's --tests filters match its task.
--no-build-cache makes every run execute its tests. The results, as committed, are in w4.10-review43.log.
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

TURRET = 'sim/src/main/java/com/xpdustry/shard/sim/blocks/turret/TurretBuild.java'
FOG = 'sim/src/main/java/com/xpdustry/shard/sim/fog/FogControl.java'
GROUP = 'sim/src/main/java/com/xpdustry/shard/sim/ai/command/UnitGroup.java'

SIM = [
    ('clean', None, None, None),
    ('turret: lastRangeChange not hashed', TURRET,
     '.bool(logicShooting).f(lastRangeChange)\n', '.bool(logicShooting)\n'),
]

ORACLE = [
    ('clean', None, None, None),
    ('fog: the dynamic circle not one wider', FOG,
     'circle(data.write, x(event), y(event), rad + 1);', 'circle(data.write, x(event), y(event), rad);'),
    ('fog: a loaded chunk sets each run one tile short', FOG,
     'if((run & 0x80) != 0) set(data.staticData, pos, pos + consec);', 'if((run & 0x80) != 0) set(data.staticData, pos, pos + consec - 1);'),
    ('formation: units kept twice as far apart', GROUP,
     'float rs = (radius + other.hitSize / 2f) * 1.2f;', 'float rs = (radius + other.hitSize / 2f) * 2.4f;'),
]

runner.run(SIM, ['TurretRangeHashToggleTest'], [':sim:test', '--no-build-cache'], ['sim/build/test-results/test'])
runner.run(ORACLE, ['FogOutcomeOracleTest', 'FogSaveOracleTest', 'MoveOutcomeOracleTest'], [':compat:oracleTest', '--no-build-cache'],
           ['compat/build/test-results/oracleTest'])
