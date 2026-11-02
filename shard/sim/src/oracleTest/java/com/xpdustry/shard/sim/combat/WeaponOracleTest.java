package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.geom.Vec2;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate WP43: every weapon of every vanilla unit type fires for {@value #FRAMES} frames in vanilla and in Shard, and
 * each frame the mounts (reload, rotation, shots, heat, recoil, warmup, side), the shooter, the bullets made (id, type,
 * spawn position), the entity id counter and the random state are equal. A weapon fires with its mirror
 * ({@code otherSide}), the unit's other weapons idle; the unit turns to the aim point. A point-defense weapon has
 * enemy bullets to shoot down, a repair beam a damaged ally.
 *
 * <p>Both sides run the type's abilities (WP62) and remove units the weapon spawns at the end of each frame (missiles
 * fly by {@code MissileAI}, WP54), with fire on: incendiary and liquid bullets start real fires and puddles. {@link #GAPS}
 * lists the weapons whose bullets need what Shard doesn't port yet; they must still fail, so the list shrinks as those
 * packages land. It is empty since the WP61 bullet kinds landed.
 */
class WeaponOracleTest{
    static final int SIZE = 100, FRAMES = 600;
    static final float X = 400f, Y = 400f, AIM_X = 476f, AIM_Y = 436f;
    /** Weapons that diverge for a reason outside WP41/WP43, as "unit/index": the reason. */
    static final Map<String, String> GAPS = new TreeMap<>();

    static void gaps(String reason, String... weapons){
        for(String weapon : weapons) GAPS.put(weapon, reason);
    }

    @Test
    void everyWeaponShootsAsVanilla() throws IOException{
        com.xpdustry.shard.oracle.Vanilla.boot();
        var results = new TreeMap<String, String>();
        int weapons = 0;
        for(UnitType type : content.units()){
            if(type.weapons.isEmpty() || type.internal) continue;
            weapons += type.weapons.size;
            var done = new boolean[type.weapons.size];
            for(int i = 0; i < type.weapons.size; i++){
                if(done[i]) continue;
                int other = type.weapons.get(i).otherSide;
                int[] group = other > i && other < type.weapons.size ? new int[]{i, other} : new int[]{i};
                for(int index : group) done[index] = true;
                String outcome = run(type, group);
                for(int index : group) results.put(type.name + "/" + index + " " + type.weapons.get(index).name, outcome);
            }
        }
        var report = new StringBuilder("weapons: " + weapons + "\n");
        results.forEach((weapon, outcome) -> report.append(weapon).append(": ").append(outcome).append('\n'));
        Path out = Path.of("build", "combat-oracle", "weapons.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);

        var failed = new TreeMap<String, String>();
        results.forEach((weapon, outcome) -> {
            if(!outcome.startsWith("ok")) failed.put(weapon.substring(0, weapon.indexOf(' ')), outcome);
        });
        assertEquals(149, weapons, "vanilla's weapons, mirrors included");
        var unexpected = new TreeMap<>(failed);
        unexpected.keySet().removeAll(GAPS.keySet());
        var fixed = new TreeSet<>(GAPS.keySet());
        fixed.removeAll(failed.keySet());
        assertEquals(Map.of(), unexpected, "weapons that diverge, beyond the listed gaps");
        assertEquals(Set.of(), fixed, "listed gaps that now match vanilla: take them off the list");
    }

    /** Fires {@code group} of {@code type} for {@value #FRAMES} frames; "ok, N shots" or where it diverged. */
    static String run(UnitType type, int[] group){
        try(var o = new CombatOracle(SIZE, type.naval ? "deep-water" : "stone")){
            com.xpdustry.shard.content.UnitDef def = CombatOracle.VANILLA.unit(type.name);
            boolean pointDefense = false, repair = false;
            for(int index : group){
                pointDefense |= type.weapons.get(index) instanceof mindustry.type.weapons.PointDefenseWeapon;
                repair |= type.weapons.get(index) instanceof mindustry.type.weapons.RepairBeamWeapon;
            }
            mindustry.gen.Unit v = type.spawn(Team.sharded, X, Y);
            Unit s = Unit.spawn(o.world, def, Team.sharded.id, X, Y, 0f);
            v.controller(new VanillaAim(group));
            s.controller(new ShardAim(group));
            mindustry.gen.Unit vAlly = null;
            Unit sAlly = null;
            if(repair){
                vAlly = content.unit("dagger").spawn(Team.sharded, X + 24f, Y + 8f);
                sAlly = Unit.spawn(o.world, CombatOracle.VANILLA.unit("dagger"), Team.sharded.id, X + 24f, Y + 8f, 0f);
                vAlly.controller(new VanillaAim(new int[0]));
                sAlly.controller(new ShardAim(new int[0]));
            }
            var keep = List.of(v.id, vAlly == null ? -1 : vAlly.id);
            var shardKeep = List.of(s.id(), sAlly == null ? -1 : sAlly.id());
            BulletType threat = o.world.bullets().types(o.world).get(CombatOracle.VANILLA.bullet(content.unit("dagger").weapons.first().bullet.id));
            mindustry.entities.bullet.BulletType vThreat = content.unit("dagger").weapons.first().bullet;
            int shots = 0;
            for(int frame = 0; frame < FRAMES; frame++){
                String at = type.name + " " + Arrays.toString(group) + " frame " + frame;
                if(pointDefense && frame % 15 == 0){
                    float angle = frame * 7f % 360f;
                    float sx = X + 100f * arc.math.Angles.trnsx(angle, 1f), sy = Y + 100f * arc.math.Angles.trnsy(angle, 1f);
                    vThreat.create(null, Team.crux, sx, sy, angle + 180f);
                    threat.create(o.world, null, Team.crux.id, sx, sy, angle + 180f);
                }
                if(repair && frame % 60 == 0){
                    vAlly.health = vAlly.maxHealth * 0.4f;
                    sAlly.health = sAlly.maxHealth * 0.4f;
                }
                o.frame();
                try{
                    o.sameRandom(at);
                    o.sameIds(at);
                    assertEquals(o.vanillaCreated(), o.shardCreated(), at + ": bullets made");
                    assertEquals(v.isAdded(), s.isAdded(), at + ": shooter added");
                    if(v.isAdded()){
                        CombatOracle.same(at + " x", v.x, s.x);
                        CombatOracle.same(at + " y", v.y, s.y);
                        CombatOracle.same(at + " rotation", v.rotation, s.rotation);
                        CombatOracle.same(at + " vel.x", v.vel.x, s.vel.x);
                        CombatOracle.same(at + " vel.y", v.vel.y, s.vel.y);
                        for(int i = 0; i < v.mounts.length; i++){
                            var vm = v.mounts[i];
                            var sm = s.mounts[i];
                            String m = at + " mount " + i;
                            CombatOracle.same(m + " reload", vm.reload, sm.reload);
                            CombatOracle.same(m + " rotation", vm.rotation, sm.rotation);
                            CombatOracle.same(m + " heat", vm.heat, sm.heat);
                            CombatOracle.same(m + " recoil", vm.recoil, sm.recoil);
                            CombatOracle.same(m + " warmup", vm.warmup, sm.warmup);
                            CombatOracle.same(m + " aimX", vm.aimX, sm.aimX);
                            CombatOracle.same(m + " aimY", vm.aimY, sm.aimY);
                            assertEquals(vm.totalShots, sm.totalShots, m + " shots");
                            assertEquals(vm.side, sm.side, m + " side");
                            assertEquals(vm.shoot, sm.shoot, m + " shoot");
                        }
                        shots = 0;
                        for(int index : group) shots += v.mounts[index].totalShots;
                    }
                    if(repair){
                        CombatOracle.same(at + " ally health", vAlly.health, sAlly.health);
                    }
                    assertEquals(o.vanillaBullets(), o.shardBullets(), at + ": live bullets");
                }catch(AssertionError e){
                    return "diverged: " + e.getMessage();
                }
                // what the weapon spawned (missiles) leaves at once, on both sides
                var vSpawned = new ArrayList<mindustry.gen.Unit>();
                mindustry.gen.Groups.unit.each(u -> !keep.contains(u.id), vSpawned::add);
                var sSpawned = new ArrayList<Unit>();
                for(int i = 0; i < o.world.units().size(); i++){
                    Unit u = o.world.units().get(i);
                    if(!shardKeep.contains(u.id())) sSpawned.add(u);
                }
                if(vSpawned.size() != sSpawned.size()) return "diverged: " + at + ": " + vSpawned.size() + " spawned units, Shard " + sSpawned.size();
                vSpawned.forEach(mindustry.gen.Unit::remove);
                sSpawned.forEach(Unit::remove);
            }
            return "ok, " + shots + " shots";
        }catch(RuntimeException e){
            var trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            return "threw: " + trace.toString().lines().limit(6).reduce((a, b) -> a + " | " + b).orElse("");
        }
    }

    static boolean in(int[] group, int index){
        for(int i : group) if(i == index) return true;
        return false;
    }

    /** Turns to the aim point, aims every controllable mount there, and fires the group's. */
    static final class VanillaAim implements mindustry.entities.units.UnitController{
        final int[] group;
        mindustry.gen.Unit unit;

        VanillaAim(int[] group){
            this.group = group;
        }

        @Override
        public void unit(mindustry.gen.Unit unit){
            this.unit = unit;
        }

        @Override
        public mindustry.gen.Unit unit(){
            return unit;
        }

        @Override
        public void updateUnit(){
            unit.lookAt(AIM_X, AIM_Y);
            unit.aim(AIM_X, AIM_Y);
            for(int i = 0; i < unit.mounts.length; i++){
                var mount = unit.mounts[i];
                if(!mount.weapon.controllable) continue;
                mount.rotate = mount.shoot = in(group, i);
            }
            unit.isShooting = group.length > 0;
        }
    }

    static final class ShardAim implements UnitController{
        final int[] group;

        ShardAim(int[] group){
            this.group = group;
        }

        @Override
        public void updateUnit(Unit unit){
            unit.lookAt(AIM_X, AIM_Y);
            Weapons.aim(unit, AIM_X, AIM_Y);
            for(int i = 0; i < unit.mounts.length; i++){
                var mount = unit.mounts[i];
                if(!Weapon.of(unit, mount).controllable) continue;
                mount.rotate = mount.shoot = in(group, i);
            }
            unit.isShooting = group.length > 0;
        }

        @Override
        public UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
