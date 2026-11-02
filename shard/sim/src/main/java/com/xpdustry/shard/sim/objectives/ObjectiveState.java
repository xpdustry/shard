package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The part of vanilla's {@code GameState} that objectives and markers keep: {@code state.markers} ({@link #markers}),
 * {@code state.stats} ({@link #stats}) and {@code state.mapLocales} ({@link #locales}). The objectives themselves are
 * the rules' ({@link Rules#objectives}), as in vanilla.
 *
 * <p>Construction, units and cores count into {@link #stats} through the static hooks here, where vanilla's
 * {@code Logic} listens for {@code BlockBuildEndEvent}, {@code UnitDestroyEvent}, {@code UnitCreateEvent} and
 * {@code CoreBuild.handleItem} counts (Logic.java:53-232, CoreBlock.java:892); destroyed buildings through
 * {@link #destroying}, its {@code BlockDestroyEvent} listener.
 */
@Register
public final class ObjectiveState implements WorldPart, Checkpointable, DestroyWatcher{
    public MapMarkers markers = new MapMarkers();
    public GameStats stats = new GameStats();
    public MapLocales locales = new MapLocales();

    /** The world's part, or null in a world built without it. */
    public static ObjectiveState of(World world){
        return world.find(ObjectiveState.class);
    }

    /** The world's stats; a world without the part counts into a scratch copy no one reads. */
    static GameStats stats(World world){
        ObjectiveState state = of(world);
        return state == null ? new GameStats() : state.stats;
    }

    // region stat hooks

    /**
     * {@code BlockBuildEndEvent}, not breaking: {@code block} now stands on the tile, built by {@code team} or by a unit
     * of {@code unitTeam} (-1 for none).
     */
    public static void buildEnded(World world, int block, int team, int unitTeam){
        ObjectiveState state = of(world);
        if(state == null) return;
        Rules rules = world.part(WorldRules.class).rules;
        if(team == rules.defaultTeam) GameStats.increment(state.stats.placedBlockCount, block);
        if(team == rules.defaultTeam || unitTeam == rules.defaultTeam) state.stats.buildingsBuilt++;
    }

    /** {@code BlockBuildEndEvent}, breaking. */
    public static void breakEnded(World world, int team, int unitTeam){
        ObjectiveState state = of(world);
        if(state == null) return;
        Rules rules = world.part(WorldRules.class).rules;
        if(team == rules.defaultTeam || unitTeam == rules.defaultTeam) state.stats.buildingsDeconstructed++;
    }

    /** {@code UnitDestroyEvent}. */
    public static void unitDestroyed(World world, int team){
        ObjectiveState state = of(world);
        if(state != null && team != world.part(WorldRules.class).rules.defaultTeam) state.stats.enemyUnitsDestroyed++;
    }

    /** {@code UnitCreateEvent} (Logic.java:228): a factory, reconstructor or spawn ability made a unit of {@code team}. */
    public static void unitCreated(World world, int team){
        ObjectiveState state = of(world);
        if(state != null && team == world.part(WorldRules.class).rules.defaultTeam) state.stats.unitsCreated++;
    }

    /** {@code CoreBuild.handleItem}: an item reached a core of {@code team} through a block. */
    public static void coreItem(World world, int team, int item){
        ObjectiveState state = of(world);
        if(state != null && team == world.part(WorldRules.class).rules.defaultTeam) GameStats.increment(state.stats.coreItemCount, item);
    }

    /**
     * {@code BlockDestroyEvent} (Logic.java:202): a building of the default team counts in {@code buildingsDestroyed};
     * any other team's (derelict included) by its block, construction sites as their {@code build<size>} block.
     */
    @Override
    public void destroying(Building building){
        if(building.team == building.world().part(WorldRules.class).rules.defaultTeam) stats.buildingsDestroyed++;
        else GameStats.increment(stats.destroyedBlockCount, building.block().id());
    }

    // endregion

    @Override
    public WorldPart copy(CopyContext context){
        var out = new ObjectiveState();
        out.markers = markers.copy();
        out.stats = stats.copy();
        out.locales = locales.copy();
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        markers.hash(hasher);
        stats.hash(hasher);
        locales.hash(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.obj("markers", markers).obj("stats", stats).obj("locales", locales);
    }

    @Override
    public void read(CheckpointIn in){
        markers = in.obj("markers", MapMarkers.class);
        stats = in.obj("stats", GameStats.class);
        locales = in.obj("locales", MapLocales.class);
    }
}
