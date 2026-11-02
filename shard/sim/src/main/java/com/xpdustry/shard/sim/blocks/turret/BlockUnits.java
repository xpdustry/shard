package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code BlockUnitComp} and {@code ControlBlock}: the unit a player controls a turret through. It is never
 * added to the world; it takes the turret's position, team, health and a hit size of 70% of the block, is valid while
 * the turret is ({@link Unit#blockTile}), and a player who controls it aims and fires the turret
 * ({@link TurretBuild#updateTile}). The wire names it by its turret's position ({@code TypeIO.writeUnit}, kind 1).
 *
 * <p>Its {@code damage(amount, withEffect)}, {@code killed()} and {@code team(team)} pass on to the turret
 * ({@link Unit#damage(float, boolean)}, {@link Unit#killed()}, {@link Unit#team(int)}): a player controlling the turret
 * moves it to the player's team every update. Bullets and abilities never reach it, as it is outside the world;
 * {@code kill()} only sends {@code unitDeath}, which finds no unit of its id. Not ported: {@code ammo}, which only the
 * controlling client's HUD reads.
 */
public final class BlockUnits{
    /** {@code GlobalVars.ctrlPlayer}: what {@code sensor @controlled} reads for a player-controlled block. */
    public static final int CTRL_PLAYER = 2;
    private static final String TYPE = "block";

    private BlockUnits(){
    }

    /**
     * The block unit of {@code turret}, under the id it drew when it was constructed: its stats and health, not yet its
     * position, which the turret's update and {@link TurretBuild#blockUnit()} sync (a building being constructed may
     * stand nowhere yet).
     */
    static Unit create(TurretBuild turret){
        World world = turret.world();
        UnitDef type = world.content().unit(TYPE);
        // UnitTypes.block's controller: a player team's CommandAI, whose draws were made with the unit's
        Unit unit = Unit.createDrawn(world, type, turret.team, turret.blockUnitId, CommandAi.blank());
        unit.blockTile = turret;
        stats(unit, turret);
        unit.health = turret.health;
        return unit;
    }

    /** {@code BlockUnitComp.tile(tile)}: the building's stats and position. */
    static void tile(Unit unit, Building tile){
        stats(unit, tile);
        unit.health = tile.health;
        unit.set(tile.x(), tile.y());
    }

    /**
     * The stats {@code tile(tile)} sets from the block: the maximum health, and a hit size of 70% of the block. A unit's
     * restore derives them from its type ({@link Unit#restoreType()}), so a restored turret sets them again.
     */
    static void stats(Unit unit, Building tile){
        unit.maxHealth = tile.block().health();
        unit.hitSize = tile.block().size() * Footprint.TILE_SIZE * 0.7f;
    }

    /**
     * {@code TypeIO.readUnit}'s kind 1: the block unit of the building at {@code pos} ({@code Point2.pack}), or null. A
     * pure read: the unit exists since its turret's construction, and its stats are as the turret's last update synced
     * them, so a client's reference to it, admitted or refused, changes nothing (W5.5's F3).
     */
    public static Unit at(World world, int pos){
        Building building = world.buildings().at(com.xpdustry.shard.common.geom.Point2.x(pos), com.xpdustry.shard.common.geom.Point2.y(pos));
        return building instanceof TurretBuild turret ? turret.blockUnit : null;
    }

    /** {@code ControlBlock.canControl()}: whether players may take this turret over. */
    public static boolean canControl(Building building){
        return building instanceof TurretBuild turret && turret.type().playerControllable;
    }
}
