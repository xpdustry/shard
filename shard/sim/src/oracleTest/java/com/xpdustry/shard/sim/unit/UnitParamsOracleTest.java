package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.ai.types.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: every vanilla unit type's {@link UnitParams}, read from the vanilla epoch, equal the booted oracle's live
 * {@code UnitType}: its fields, the components its entity class implements (one entity of each type is constructed to
 * find out), and the controller {@code createController} gives a new unit, on a player team and on an AI team.
 */
class UnitParamsOracleTest{
    @Test
    void everyUnitTypeMatchesTheLiveOne(){
        Vanilla.boot();
        assertEquals(content.units().size, VanillaWorlds.VANILLA.units());
        var wrong = new ArrayList<String>();
        var table = UnitTable.of(VanillaWorlds.VANILLA);
        for(UnitType type : content.units()){
            UnitDef def = VanillaWorlds.VANILLA.unit(type.id);
            assertEquals(type.name, def.name());
            UnitParams params = UnitParams.of(def);
            mindustry.gen.Unit probe = type.constructor.get();
            var check = new Check(wrong, type.name);
            check.of("health", type.health, def.health());
            check.of("locomotion", locomotion(probe), params.locomotion());
            check.of("payloads", probe instanceof Payloadc, params.payloads());
            check.of("tethered", probe instanceof BuildingTetherc, params.tethered());
            check.of("timedKill", probe instanceof TimedKillc, params.timedKill());
            check.of("hitSize", type.hitSize, params.hitSize());
            check.of("drag", type.drag, params.drag());
            check.of("armor", type.armor, params.armor());
            check.of("flying", type.flying, params.flying());
            check.of("physics", type.physics, params.physics());
            check.of("allowLegStep", type.allowLegStep, params.allowLegStep());
            check.of("legPhysicsLayer", type.legPhysicsLayer, params.legPhysicsLayer());
            check.of("hovering", type.hovering, params.hovering());
            check.of("canBoost", type.canBoost, params.canBoost());
            check.of("targetPriority", type.targetPriority, params.targetPriority());
            check.of("targetable", type.targetable, params.targetable());
            check.of("vulnerableWithPayloads", type.vulnerableWithPayloads, params.vulnerableWithPayloads());
            check.of("useUnitCap", type.useUnitCap, params.useUnitCap());
            check.of("lifetime", type.lifetime, params.lifetime());
            check.of("weapons", type.weapons.size, params.weaponCount());
            for(int i = 0; i < Math.min(type.weapons.size, params.weaponCount()); i++){
                check.of("weapons." + i + ".baseRotation", type.weapons.get(i).baseRotation, params.weaponBaseRotation(i));
            }
            check.of("abilities", type.abilities.size, params.abilities());
            check.of("legCount", type.legCount, params.legCount());
            check.of("legLength", type.legLength, params.legLength());
            check.of("legBaseOffset", type.legBaseOffset, params.legBaseOffset());
            check.of("lockLegBase", type.lockLegBase, params.lockLegBase());
            check.of("legStraightness", type.legStraightness, params.legStraightness());
            check.of("legStraightLength", type.legStraightLength, params.legStraightLength());
            check.of("baseLegStraightness", type.baseLegStraightness, params.baseLegStraightness());
            check.of("speed", type.speed, params.speed());
            check.of("accel", type.accel, params.accel());
            check.of("rotateSpeed", type.rotateSpeed, params.rotateSpeed());
            check.of("strafePenalty", type.strafePenalty, params.strafePenalty());
            check.of("boostMultiplier", type.boostMultiplier, params.boostMultiplier());
            check.of("floorMultiplier", type.floorMultiplier, params.floorMultiplier());
            check.of("riseSpeed", type.riseSpeed, params.riseSpeed());
            check.of("descentSpeed", type.descentSpeed, params.descentSpeed());
            check.of("drownTimeMultiplier", type.drownTimeMultiplier, params.drownTimeMultiplier());
            check.of("fallSpeed", type.fallSpeed, params.fallSpeed());
            check.of("wreckHealthMultiplier", type.wreckHealthMultiplier, params.wreckHealthMultiplier());
            check.of("engineSize", type.engineSize, params.engineSize());
            check.of("bounded", type.bounded, params.bounded());
            check.of("omniMovement", type.omniMovement, params.omniMovement());
            check.of("canDrown", type.canDrown, params.canDrown());
            check.of("killable", type.killable, params.killable());
            check.of("hittable", type.hittable, params.hittable());
            check.of("createWreck", type.createWreck, params.createWreck());
            check.of("emitWalkEffect", type.emitWalkEffect, params.emitWalkEffect());
            check.of("emitWalkSound", type.emitWalkSound, params.emitWalkSound());
            check.of("itemCapacity", type.itemCapacity, params.itemCapacity());
            check.of("playerControllable", type.playerControllable, params.playerControllable());
            check.of("defaultCommand", type.defaultCommand == null ? null : type.defaultCommand.name, params.defaultCommand());
            check.of("commands", type.commands.map(c -> c.name).list(), params.commands());
            for(int env : new int[]{mindustry.world.meta.Env.terrestrial, mindustry.world.meta.Env.space, mindustry.world.meta.Env.underwater, mindustry.world.meta.Env.spores, mindustry.world.meta.Env.scorching, mindustry.world.meta.Env.terrestrial | mindustry.world.meta.Env.oxygen,
                mindustry.world.meta.Env.space | mindustry.world.meta.Env.scorching, mindustry.world.meta.Env.any, 0}){
                check.of("supportsEnv(" + env + ")", type.supportsEnv(env), params.supportsEnv(env));
            }
            check.of("dummy", probe instanceof TargetDummyc, params.dummy());
            UnitParams.LegParams legs = params.legs();
            check.of("legSpeed", type.legSpeed, legs.speed());
            check.of("legGroupSize", type.legGroupSize, legs.groupSize());
            check.of("legMoveSpace", type.legMoveSpace, legs.moveSpace());
            check.of("legContinuousMove", type.legContinuousMove, legs.continuousMove());
            check.of("legForwardScl", type.legForwardScl, legs.forwardScl());
            check.of("legMinLength", type.legMinLength, legs.minLength());
            check.of("legMaxLength", type.legMaxLength, legs.maxLength());
            check.of("legPairOffset", type.legPairOffset, legs.pairOffset());
            check.of("flipBackLegs", type.flipBackLegs, legs.flipBackLegs());
            check.of("flipLegSide", type.flipLegSide, legs.flipSide());
            check.of("legLengthScl", type.legLengthScl, legs.lengthScl());
            check.of("legSplashDamage", type.legSplashDamage, legs.splashDamage());
            check.of("legSplashRange", type.legSplashRange, legs.splashRange());
            // each ability runs its own kind's port, in vanilla's order
            var abilities = table.abilities(def);
            check.of("abilities", type.abilities.size, abilities.size());
            for(int i = 0; i < Math.min(type.abilities.size, abilities.size()); i++){
                var ability = abilities.get(i);
                check.of("abilities." + i + ".kind", kebab(named(type.abilities.get(i).getClass()).getSimpleName()), ability.def().kind().name());
                check.of("abilities." + i + ".supported", true, ability.supported());
            }
        }
        assertEquals(List.of(), wrong);
    }

    /**
     * {@code createController} on a player team with the default rules, and on the wave team with waves on: the Shard
     * controller is the same class, an AI of the same kind.
     */
    @Test
    void controllersMatchTheLiveOnes(){
        Vanilla.boot();
        var wrong = new ArrayList<String>();
        for(boolean waves : new boolean[]{false, true}){
            Vanilla.emptyWorld(16, 16);
            state.rules.waves = waves;
            World world = VanillaWorlds.world(16, 16, 1);
            world.part(WorldRules.class).rules.waves = waves;
            Team team = waves ? state.rules.waveTeam : state.rules.defaultTeam;
            for(UnitType type : content.units()){
                mindustry.gen.Unit probe = type.create(team);
                Unit unit = Unit.create(world, VanillaWorlds.VANILLA.unit(type.id), team.id);
                String vanilla = kind(probe.controller()), shard = kind(unit.controller);
                if(!vanilla.equals(shard)) wrong.add(type.name + (waves ? " (AI team)" : "") + ": " + shard + ", vanilla " + vanilla);
                if(probe.controller() instanceof CommandAI ai && unit.controller instanceof CommandAi shardAi){
                    String command = shardAi.command < 0 ? null : VanillaWorlds.VANILLA.unitCommands().get(shardAi.command);
                    if(!Objects.equals(ai.command == null ? null : ai.command.name, command)){
                        wrong.add(type.name + ": command " + command + ", vanilla " + ai.command);
                    }
                }
                if(Float.floatToIntBits(probe.shield) != Float.floatToIntBits(unit.shield)){
                    wrong.add(type.name + ": created shield " + unit.shield + ", vanilla " + probe.shield);
                }
                for(int i = 0; i < probe.abilities.length; i++){
                    if(Float.floatToIntBits(probe.abilities[i].data) != Float.floatToIntBits(unit.abilities[i].data)){
                        wrong.add(type.name + ": ability " + i + " data " + unit.abilities[i].data + ", vanilla " + probe.abilities[i].data);
                    }
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** The class, or for content's anonymous subclasses ({@code new RepairFieldAbility(){{...}}}) the one it extends. */
    static Class<?> named(Class<?> type){
        return type.isAnonymousClass() ? type.getSuperclass() : type;
    }

    /** A vanilla class's simple name as a kind key: {@code ForceFieldAbility} is {@code force-field-ability}. */
    static String kebab(String name){
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    /** A controller's class as one name: {@code FlyingFollowAI} is {@code flying-follow-ai}, as the content names it. */
    static String kind(Object controller){
        return switch(controller){
            case CommandAI ai -> "command";
            case CommandAi ai -> "command";
            case NoAI ai -> "no-ai";
            case NoAi ai -> "no-ai";
            case AiController ai -> ai.kind;
            default -> controller.getClass().getSimpleName().replace("AI", "Ai").replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
        };
    }

    static Locomotion locomotion(mindustry.gen.Unit probe){
        if(probe instanceof Mechc) return Locomotion.MECH;
        if(probe instanceof Legsc) return Locomotion.LEGS;
        if(probe instanceof Tankc) return Locomotion.TANK;
        if(probe instanceof Crawlc) return Locomotion.CRAWL;
        if(probe instanceof WaterMovec) return Locomotion.NAVAL;
        if(probe instanceof ElevationMovec) return Locomotion.HOVER;
        return Locomotion.FLYING;
    }

    record Check(List<String> wrong, String type){
        void of(String field, Object vanilla, Object shard){
            if(!Objects.equals(vanilla, shard)) wrong.add(type + "." + field + ": " + shard + ", vanilla " + vanilla);
        }
    }
}
