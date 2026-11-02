package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The building half of targeting, over the per-team building chunk buckets (note 29 §1.2): vanilla's
 * {@code BlockIndexer.findEnemyTile} and {@code TeamData.turretTree}. {@link Buildings#targets()} implements it over its
 * indexes (WP20); a world finds a part that implements it first, so a test can stand in.
 */
public interface BuildingTargets{
    /** No buildings: every lookup finds nothing. */
    BuildingTargets NONE = new BuildingTargets(){
        @Override
        public Building findEnemyTile(int team, float x, float y, float range, BuildingFilter filter, int source){
            return null;
        }

        @Override
        public boolean anyEnemyTurret(int team, float x, float y, float width, float height){
            return false;
        }
    };

    /**
     * {@code BlockIndexer.findEnemyTile(team, x, y, range, UnitSorts.buildingDefault, filter, source)}: over the active
     * enemy teams, the targetable building ({@code source}'s own count as targetable; -1 for none) within
     * {@code range} of its edge, by block priority then distance.
     */
    Building findEnemyTile(int team, float x, float y, float range, BuildingFilter filter, int source);

    /**
     * The turret half of {@code Units.nearEnemy}: whether {@code turretTree.any(x, y, width, height)} holds for a present
     * team other than {@code team} and derelict.
     */
    boolean anyEnemyTurret(int team, float x, float y, float width, float height);

    /** The world's building targets: a part that implements this, else its buildings' indexes ({@link Buildings#targets()}). */
    static BuildingTargets of(World world){
        BuildingTargets targets = world.find(BuildingTargets.class);
        return targets == null ? world.buildings().targets() : targets;
    }
}
