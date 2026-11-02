package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.sim.world.*;

/**
 * What a team's clean-up tells clients with a Call ({@link DeadTeams}), as domain events, published after the Call's
 * local effects. Positions are {@code Point2.pack(x, y)}, as vanilla's {@code build.pos()}.
 */
public sealed interface TeamEvent extends WorldEvent{
    long tick();

    /** {@code Call.setTeams(positions, team)} (Tile.java:861): the buildings at {@code positions} moved to {@code team}. */
    record SetTeams(long tick, int[] positions, int team) implements TeamEvent{
        public SetTeams{
            positions = positions.clone();
        }

        @Override
        public int[] positions(){
            return positions.clone();
        }

        @Override
        public boolean equals(Object other){
            return other instanceof SetTeams o && o.tick == tick && o.team == team && java.util.Arrays.equals(o.positions, positions);
        }

        @Override
        public int hashCode(){
            return java.util.Objects.hash(tick, team, java.util.Arrays.hashCode(positions));
        }

        @Override
        public String toString(){
            return "SetTeams[tick=" + tick + ", positions=" + java.util.Arrays.toString(positions) + ", team=" + team + "]";
        }
    }

    /**
     * {@code Call.destroyPayload(build)} (Teams.java:506): the building at {@code pos} dumped the unit it carried, which
     * was then killed.
     */
    record DestroyPayload(long tick, int pos) implements TeamEvent{
    }
}
