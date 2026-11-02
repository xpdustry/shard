"""W5.8's one-line breaks: one or more per wave-4a gap its fixtures close (the reconstructor's refusals, the payload
blocks' liquid pour, battery and large variants, the cargo AI's stale and destroyed points and PrebuildAI's ore search,
the fog's large units, removed sources, undiscovered tiles and AI-only teams), and the transport clock seam ServerTest's
quiet client runs on. Each is applied alone to a clean checkout and run against its fixtures; a break must fail one.
Two clean runs come first, and must pass (the runner labels the second, which edits nothing, MISSED when it passes).

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w5.8-wave4a-gaps.py'); the
results, as committed, are in w5.8-wave4a-gaps.log: 21 of 21 caught, each first failing at its own break (VM, -j 3,
1,455 s for the oracle breaks, 61 s for the seam's), on the fixtures of 39e4f2c7.
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

SIM = 'sim/src/main/java/com/xpdustry/shard/sim/'
RECON = SIM + 'blocks/units/Reconstructor.java'
UNIT_BUILD = SIM + 'blocks/units/UnitBuild.java'
PAYLOAD = SIM + 'blocks/payload/'
CARGO = SIM + 'ai/CargoAi.java'
PREBUILD = SIM + 'ai/PrebuildAi.java'
FOG = SIM + 'fog/FogControl.java'
BUILD = SIM + 'build/Build.java'
SERVER = 'server/src/main/java/com/xpdustry/shard/server/Server.java'
TRANSPORT = 'net/src/main/java/com/xpdustry/shard/net/Transport.java'

ORACLES = ['ReconstructorRefusalOracleTest', 'PayloadGapsOracleTest', 'CargoGapsOracleTest', 'FogGapsOracleTest', 'FogAiTeamOracleTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    ('clean, again', None, None, None),
    # W4.1: the reconstructor
    ('reconstructor: takes from its front', RECON,
     ' && relativeTo(source) != rotation\n', '\n'),
    ('reconstructor: takes a banned upgrade', RECON,
     '(teamIsAI() || unlockedNow(upgrade)) && !banned(upgrade);', '(teamIsAI() || unlockedNow(upgrade));'),
    ('reconstructor: hasUpgrade asks about the upgrade', RECON,
     '(unlockedNow(t) || teamIsAI()) && !banned(type);', '(unlockedNow(t) || teamIsAI()) && !banned(t);'),
    ('reconstructor: takes an unresearched upgrade', RECON,
     'return upgrade != -1 && (teamIsAI() || unlockedNow(upgrade))', 'return upgrade != -1 && (teamIsAI() || true)'),
    ('reconstructor: an AI team needs research too', RECON,
     'return t != -1 && (unlockedNow(t) || teamIsAI()) && !banned(type);', 'return t != -1 && unlockedNow(t) && !banned(type);'),
    ('unit blocks: everything researched in the campaign', UNIT_BUILD,
     'if(!UnitCalls.isCampaign(rules())) return true;', 'if(true) return true;'),
    # W4.4: payload blocks
    ('deconstructor: pours by the payload\'s size (Q225 undone)', PAYLOAD + 'PayloadDeconstructor.java',
     'int size = block().size();', 'int size = build.block().size();'),
    ('loader: charges by its whole input', PAYLOAD + 'PayloadLoaderBuild.java',
     'float availableInput = Math.max(powerInput - params.basePowerUse(), 0f);', 'float availableInput = Math.max(powerInput, 0f);'),
    ('unloader: drains half as fast', PAYLOAD + 'PayloadUnloader.java',
     'float unloaded = Math.min(params.maxPowerUnload() * edelta(), total);', 'float unloaded = Math.min(params.maxPowerUnload() * 0.5f * edelta(), total);'),
    ('large constructor: refuses its smallest size', PAYLOAD + 'Constructor.java',
     'def.size() >= min && def.size() <= max', 'def.size() > min && def.size() <= max'),
    ('large driver: charges a tick longer', PAYLOAD + 'PayloadMassDriver.java',
     'f.f("reload"), f.f("chargeTime"),', 'f.f("reload"), f.f("chargeTime") + 1f,'),
    # W4.7: CargoAI and PrebuildAI
    ('cargo: stale points taken first', CARGO,
     '                if(!target.stale){\n                    unloadTarget = target;\n                    break outer;',
     '                if(true){\n                    unloadTarget = target;\n                    break outer;'),
    ('cargo: gives up a destroyed point', CARGO,
     'if(unloadTarget.item != itemTarget || unloadTarget.isPayload()){', 'if(unloadTarget.item != itemTarget || unloadTarget.isPayload() || !unloadTarget.isAdded()){'),
    ('prebuild: ore searched from the core', PREBUILD,
     'ore = packed(ores.findClosestOre(unit.x, unit.y, targetItem));', 'ore = packed(ores.findClosestOre(core.x(), core.y(), targetItem));'),
    # W4.3: fog
    ('fog: every unit seen by its centre', FOG,
     'if(unit.hitSize <= 16f) return', 'if(true) return'),
    ('fog: a removed source redraws nothing', FOG,
     '        FogData data = data(world.tiles().team(pos));\n        if(data != null) data.dynamicUpdated = true;\n    }\n\n    /** {@code TileChangeEvent}',
     '        FogData data = data(world.tiles().team(pos));\n    }\n\n    /** {@code TileChangeEvent}'),
    ('fog: a player doesn\'t make an AI team more than AI', FOG,
     'for(int i = 0; i < players.list().size(); i++) if(players.list().get(i).team == team) return false;', '// players ignored'),
    ('fog: an AI-only team\'s source discovers', FOG,
     'build.tile() != pos || isOnlyAI(world, build.team) ||', 'build.tile() != pos ||'),
    ('build: undiscovered tiles placeable', BUILD,
     '|| (rules.staticFog && rules.fog && !FogControl.discoveredTile(world, team, cx, cy))', '|| false'),
]

SEAM = [
    ('clean', None, None, None),
    ('server: the transport ignores the clock it was given', SERVER,
     'transportConfig, flow, options.netClock());', 'transportConfig, flow);'),
    ('transport: the read timeout counts from the accept', TRANSPORT,
     'if(now - conn.lastReadNanos > readTimeout){', 'if(now - conn.acceptedNanos > readTimeout){'),
]

runner.run(BREAKS, ORACLES, [':compat:oracleTest', '--no-build-cache'], ['compat/build/test-results/oracleTest'])
runner.run(SEAM, ['ServerTest'], [':server:test', '--no-build-cache'], ['server/build/test-results/test'])
