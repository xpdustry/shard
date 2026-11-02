"""W3.10's break gate: break one line per ability kind, run its fixture, expect it to fail. Run from the VM's copy of shard/: python3 compat/src/oracleTest/ability-breaks.py [names]."""
import shutil, subprocess, sys

A = 'sim/src/main/java/com/xpdustry/shard/sim/unit/ability/'
F = '*AbilityOracleTest.'
U = '*UncarriedAbilityOracleTest*'
BREAKS = [
    ('force-field', A + 'ForceFieldAbility.java', 'unit.shield -= cooldown * regen;', 'unit.shield -= 0f;', F + 'quasar*'),
    ('repair-field', A + 'RepairFieldAbility.java', 'other.heal((amount + healPercentMult * maxHealth) * healMult);', 'other.heal(amount + healPercentMult * maxHealth);', F + 'nova*'),
    ('status-field', A + 'StatusFieldAbility.java', 'state.timer >= reload &&', 'state.timer > reload &&', F + 'naval*'),
    ('shield-regen', A + 'ShieldRegenFieldAbility.java', 'Math.min(other.shield + amount, max)', 'Math.min(other.shield + amount / 2f, max)', F + 'pulsar*'),
    ('shield-arc', A + 'ShieldArcAbility.java', 'Mathf.random(rand, 0.9f, 1.1f);', '', F + 'tecta*'),
    ('energy-field', A + 'EnergyFieldAbility.java', 'if(anyNearby) Mathf.range(world.rand(), 0.1f);', '', F + 'naval*'),
    ('suppression', A + 'SuppressionFieldAbility.java', '(state.timer += 1f) >= maxDelay', '(state.timer += 1f) > maxDelay', F + 'quell*'),
    ('spawn-death', A + 'SpawnDeathAbility.java', 'amount + Mathf.random(rand, randAmount)', 'amount', F + 'latum*'),
    ('liquid-regen', A + 'LiquidRegenAbility.java', 'unit.heal(fractionTaken * regenPerSlurp);', 'unit.heal(fractionTaken);', F + 'latum*'),
    ('liquid-explode', A + 'LiquidExplodeAbility.java', 'amount * scaling)', 'amount * scaling * 0.5f)', F + 'latum*'),
    ('regen', A + 'RegenAbility.java', 'percentAmount / 100f + amount', 'percentAmount / 50f + amount', F + 'latum*'),
    ('armor-plate', A + 'ArmorPlateAbility.java', 'unit.isShooting ? 1f : 0f, 0.1f', 'unit.isShooting ? 1f : 0f, 0.2f', U),
    ('move-lightning', A + 'MoveLightningAbility.java', 'sx + unit.vel.x, sy + unit.vel.y', 'sx, sy', U),
    ('unit-spawn', A + 'UnitSpawnAbility.java', 'u.rotation = carrier.rotation;', '', U),
    ('unit-sink', 'compat/src/main/java/com/xpdustry/shard/compat/v160/entity/UnitSink.java', 'i < abilities.length ? abilities[i] : 0f',
     'abilities.length == unit.abilities.length ? abilities[i] : 0f', '*EntitySyncOracleTest.savedAbilityCounts*'),
]

only = set(sys.argv[1:])
results = []
for name, path, old, new, tests in BREAKS:
    if only and name not in only:
        continue
    src = open(path).read()
    if old not in src:
        results.append((name, 'MISSING LINE'))
        print(name, 'MISSING LINE', flush=True)
        continue
    shutil.copy(path, path + '.orig')
    try:
        open(path, 'w').write(src.replace(old, new, 1))
        log = subprocess.run(['./gradlew', '-q', ':compat:oracleTest', '--tests', tests], capture_output=True, text=True)
        out = log.stdout + log.stderr
        if 'compileJava FAILED' in out or ' error:' in out:
            verdict = 'COMPILE ERROR'
        elif log.returncode != 0 and 'FAILED' in out:
            verdict = 'fails (good)'
        elif log.returncode == 0:
            verdict = 'PASSES (bad)'
        else:
            verdict = 'other exit %d' % log.returncode
        detail = ''
        for line in out.splitlines():
            if 'AssertionFailedError' in line or 'Exception' in line:
                detail = line.strip()[:200]
                break
        results.append((name, verdict + ' ' + detail))
    finally:
        shutil.move(path + '.orig', path)
    print(name, results[-1][1], flush=True)

print('==== breaks')
for name, verdict in results:
    print(name.ljust(16), verdict)
