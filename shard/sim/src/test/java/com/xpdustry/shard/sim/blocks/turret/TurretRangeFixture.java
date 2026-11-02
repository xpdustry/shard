package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The turret world of {@link TurretRangeHashToggleTest}: a sharded duo fed copper and then, at tick
 * {@value #GRAPHITE_AT}, graphite (whose {@code rangeChange} is 16, {@code Blocks.java:3295}), firing at crux walls in
 * front of a crux core. The source uses only APIs main had at {@code 9ae67530}, before W4.3's {@code lastRangeChange},
 * so the same file gives that commit's hash. {@link TurretRangeHashToggleTest} also calls it in a class loader whose
 * {@code TurretBuild} doesn't hash the field, so it returns plain JDK values.
 */
public final class TurretRangeFixture{
    public static final int TICKS = 600, GRAPHITE_AT = 120, X = 10, Y = 16;
    /** The world parts added since {@code 9ae67530}, left out of the hash; by name, since they don't exist there. */
    static final Set<String> LATER = Set.of("com.xpdustry.shard.sim.fog.FogControl", "com.xpdustry.shard.sim.ai.command.CommandBatches",
        "com.xpdustry.shard.sim.ai.team.TeamAis");

    private TurretRangeFixture(){
    }

    /** The built-in systems without {@link #LATER}: the parts main had at {@code 9ae67530}. */
    public static Systems before(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(!LATER.contains(part.getName())) parts.add(part);
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    /** The fixture world with {@code systems}, before its first tick; {@code fog} turns on {@code rules.fog} and static fog. */
    public static World world(Systems systems, boolean fog){
        World world = VanillaWorlds.world(32, 32, 3, systems);
        Rules rules = world.part(WorldRules.class).rules;
        rules.waves = false;
        if(fog){
            rules.fog = true;
            rules.staticFog = true;
        }
        world.buildings().setBlock(X, Y, VanillaWorlds.VANILLA.block("duo"), 1, 0, Cause.SERVER);
        for(int y = 12; y <= 20; y += 2) world.buildings().setBlock(20, y, VanillaWorlds.VANILLA.block("copper-wall"), 2, 0, Cause.SERVER);
        world.buildings().setBlock(26, 16, VanillaWorlds.VANILLA.block("core-shard"), 2, 0, Cause.SERVER);
        feed(world, "copper", 4);
        return world;
    }

    public static TurretBuild duo(World world){
        return (TurretBuild)world.buildings().at(X, Y);
    }

    public static void feed(World world, String item, int amount){
        for(int i = 0; i < amount; i++) duo(world).handleItem(null, VanillaWorlds.VANILLA.item(item).id());
    }

    /** One tick of the script: graphite at {@value #GRAPHITE_AT}. */
    public static void tick(World world){
        if(world.ticks() == GRAPHITE_AT) feed(world, "graphite", 10);
        world.tick();
    }

    /**
     * {@code [the hash, its sections, the duo's total shots, its top ammo's item id, the largest |lastRangeChange| on
     * any tick]} after {@value #TICKS} ticks without fog, the later parts left out. The last is "absent" where the
     * field doesn't exist; it is read by name so this compiles at {@code 9ae67530}.
     */
    public static List<String> nonFog(){
        World world = world(before(), false);
        float largest = 0f;
        boolean absent = false;
        for(int tick = 0; tick < TICKS; tick++){
            tick(world);
            Float field = lastRangeChange(duo(world));
            if(field == null) absent = true;
            else largest = Math.max(largest, Math.abs(field));
        }
        TurretBuild duo = duo(world);
        laterStats(world);
        WorldHash hash = world.hash();
        return List.of(String.format("0x%016xL", hash.value()), hash.toString(), Integer.toString(duo.totalShots),
            Integer.toString(duo.ammoIds[duo.ammoSize - 1]), absent ? "absent" : Float.toString(largest));
    }

    /**
     * W5.7 counts destroyed buildings ({@code destroyedBlockCount}) in the game stats, which {@code 9ae67530} left at
     * zero: the duo's kills would move the hash. Left out as {@link #LATER}'s parts are, by putting back the zeros that
     * commit hashed; {@code GameStatsHashToggleTest} shows they are the stats' only change here. Likewise the block
     * unit W5.7 makes at construction.
     */
    static void laterStats(World world){
        var state = com.xpdustry.shard.sim.objectives.ObjectiveState.of(world);
        if(state != null) state.stats = new com.xpdustry.shard.sim.objectives.GameStats();
        // W5.7 makes the block unit when the duo is constructed, as vanilla does; 9ae67530 made it on first read, and
        // nothing reads it here, so that commit hashed none
        laterBlockUnit(duo(world));
    }

    /** Drops the block unit by reflection, so this file still compiles where the field isn't public. */
    static void laterBlockUnit(TurretBuild duo){
        try{
            TurretBuild.class.getField("blockUnit").set(duo, null);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    static Float lastRangeChange(TurretBuild duo){
        try{
            return TurretBuild.class.getField("lastRangeChange").getFloat(duo);
        }catch(NoSuchFieldException e){
            return null;
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }
}
