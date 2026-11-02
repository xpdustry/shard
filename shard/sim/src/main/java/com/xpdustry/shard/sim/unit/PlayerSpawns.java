package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.world.*;

/**
 * Where a dead player respawns (WP23): {@code PlayerComp.update}'s {@code bestCore()} and
 * {@code core.requestSpawn(player)}. A world finds it as the first {@link WorldPart} implementing it; without one,
 * nobody respawns ({@link #NONE}).
 */
public interface PlayerSpawns{
    /** Nobody respawns: no core is found. */
    PlayerSpawns NONE = new PlayerSpawns(){
        @Override
        public boolean hasCore(Player player){
            return false;
        }

        @Override
        public void requestSpawn(Player player){
        }
    };

    /** Whether {@code bestCore()} finds a core for {@code player}'s team. */
    boolean hasCore(Player player);

    /** {@code bestCore().requestSpawn(player)}: spawns the core's unit for the player, if the core allows. */
    void requestSpawn(Player player);

    static PlayerSpawns of(World world){
        PlayerSpawns spawns = world.find(PlayerSpawns.class);
        return spawns == null ? NONE : spawns;
    }
}
