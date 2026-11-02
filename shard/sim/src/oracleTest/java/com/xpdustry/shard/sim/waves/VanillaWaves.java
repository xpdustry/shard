package com.xpdustry.shard.sim.waves;

import arc.math.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.entities.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * The hosted vanilla map as a Shard world for wave tests: its tiles ({@link VanillaMaps}), the wave rules, the spawner
 * reset as after a load, the countdown and wave, the random and the entity id counter.
 */
final class VanillaWaves{
    private VanillaWaves(){
    }

    /** A Shard world of the hosted map, in step with vanilla: same rules, wave state, random and next entity id. */
    static World world(){
        World world = VanillaMaps.load(VanillaWorlds.of(VanillaWorlds.VANILLA, mindustry.Vars.world.width(), mindustry.Vars.world.height()));
        copyRules(state.rules, world.part(WorldRules.class).rules);
        WaveSpawner.of(world).reset(world);
        WaveState waves = WaveState.of(world);
        waves.wave = state.wave;
        waves.wavetime = state.wavetime;
        sync(world);
        int next = lastId();
        if(world.ids().peek() > next) throw new IllegalStateException("Shard drew more ids loading the map than vanilla: " + world.ids().peek() + " > " + next);
        world.ids().checkNextId(next - 1);
        return world;
    }

    /** Copies vanilla's random state into the world's. */
    static void sync(World world){
        world.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
    }

    /** {@code EntityGroup.lastId}: the next entity id vanilla will draw. */
    static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /** The rules waves read, from vanilla's. */
    static void copyRules(mindustry.game.Rules from, Rules to){
        to.waves = from.waves;
        to.waveTimer = from.waveTimer;
        to.waveSending = from.waveSending;
        to.waveSpacing = from.waveSpacing;
        to.initialWaveSpacing = from.initialWaveSpacing;
        to.waitEnemies = from.waitEnemies;
        to.winWave = from.winWave;
        to.attackMode = from.attackMode;
        to.pvp = from.pvp;
        to.wavesSpawnAtCores = from.wavesSpawnAtCores;
        to.airUseSpawns = from.airUseSpawns;
        to.dropZoneRadius = from.dropZoneRadius;
        to.waveTeam = from.waveTeam.id;
        to.defaultTeam = from.defaultTeam.id;
        to.editor = from.editor;
        to.disableUnitCap = from.disableUnitCap;
        to.unitCap = from.unitCap;
        to.unitCapVariable = from.unitCapVariable;
        to.unitHealthMultiplier = from.unitHealthMultiplier;
        to.env = from.env;
        to.spawns = new ArrayList<>();
        for(mindustry.game.SpawnGroup group : from.spawns) to.spawns.add(group(group));
    }

    static SpawnGroup group(mindustry.game.SpawnGroup from){
        var out = new SpawnGroup(from.type == null ? -1 : from.type.id);
        out.end = from.end;
        out.begin = from.begin;
        out.spacing = from.spacing;
        out.max = from.max;
        out.unitScaling = from.unitScaling;
        out.shields = from.shields;
        out.shieldScaling = from.shieldScaling;
        out.unitAmount = from.unitAmount;
        out.spawn = from.spawn;
        if(from.payloads != null){
            out.payloads = new ArrayList<>();
            for(var type : from.payloads) out.payloads.add(type == null ? -1 : (int)type.id);
        }
        out.effect = from.effect == null ? -1 : from.effect.id;
        out.items = from.items == null ? null : new ItemStack(from.items.item.id, from.items.amount);
        out.team = from.team == null ? -1 : from.team.id;
        return out;
    }

    /** One unit as the wave tests compare it. */
    static String describe(mindustry.gen.Unit unit){
        var out = new StringBuilder();
        out.append('#').append(unit.id).append(' ').append(unit.type.name).append(" team ").append(unit.team.id)
            .append(" at ").append(bits(unit.x)).append(',').append(bits(unit.y)).append(" rot ").append(bits(unit.rotation))
            .append(" shield ").append(bits(unit.shield)).append(" health ").append(bits(unit.health))
            .append(" stack ").append(unit.stack.amount == 0 ? "-" : unit.stack.item.id + "x" + unit.stack.amount).append(" statuses [");
        var statuses = new ArrayList<String>();
        for(int id = 0; id < content.statusEffects().size; id++){
            var effect = content.statusEffects().get(id);
            if(unit.hasEffect(effect)) statuses.add(effect.id + ":" + bits(unit.getDuration(effect)));
        }
        out.append(String.join(" ", statuses)).append(']');
        if(unit instanceof mindustry.gen.Payloadc carrier){
            out.append(" payloads [");
            var payloads = new ArrayList<String>();
            for(var payload : carrier.payloads()){
                payloads.add(payload instanceof mindustry.world.blocks.payloads.UnitPayload u ? "#" + u.unit.id + " " + u.unit.type.name : "?");
            }
            out.append(String.join(" ", payloads)).append(']');
        }
        return out.toString();
    }

    static String describe(Unit unit){
        var out = new StringBuilder();
        int stack = unit.stackAmount;
        out.append('#').append(unit.id()).append(' ').append(unit.type().name()).append(" team ").append(unit.team)
            .append(" at ").append(bits(unit.x)).append(',').append(bits(unit.y)).append(" rot ").append(bits(unit.rotation))
            .append(" shield ").append(bits(unit.shield)).append(" health ").append(bits(unit.health))
            .append(" stack ").append(stack == 0 ? "-" : unit.stackItem + "x" + stack).append(" statuses [");
        var statuses = new ArrayList<String>();
        for(int id = 0; id < StatusTable.of(unit.world()).size(); id++){
            if(unit.statuses.hasEffect(id)) statuses.add(id + ":" + bits(unit.statuses.getDuration(id)));
        }
        out.append(String.join(" ", statuses)).append(']');
        if(unit.payloads != null){
            out.append(" payloads [");
            var payloads = new ArrayList<String>();
            for(int i = 0; i < unit.payloads.size(); i++){
                payloads.add(unit.payloads.get(i) instanceof Payload.UnitPayload(Unit u) ? "#" + u.id() + " " + u.type().name() : "?");
            }
            out.append(String.join(" ", payloads)).append(']');
        }
        return out.toString();
    }

    /** A float as its value and exact bits, so a last-bit difference shows. */
    static String bits(float value){
        return value + "(" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
    }
}
