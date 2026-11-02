package com.xpdustry.shard.server.host;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code NetServer.TeamAssigner} (NetServer.java:1478), the {@code netServer.assigner} field a plugin
 * replaces: the team a player gets when it joins a world, and on each map reload of a PvP world
 * ({@code WorldReloader.end}).
 *
 * <p>Assigners are admission policies, as {@code ChatFilter}s are (ARCHITECTURE.md §4): registered with
 * {@code @Register}, run on the world thread inside the {@link HostInputs.Join} command, deciding from the world and
 * their arguments only, so a replay assigns the same teams. Vanilla has one assigner; here the registered one with the
 * highest {@link #order()} is used (then the last by class name), so a plugin's assigner with an order above 0
 * replaces {@link VanillaTeamAssigner}.
 */
@Registry
public interface TeamAssigner{
    /** Vanilla's {@code null}: no team qualifies. The join then falls back to the rules' default team. */
    int NONE = -1;
    Comparator<TeamAssigner> ORDER = Comparator.comparingInt(TeamAssigner::order).thenComparing(assigner -> assigner.getClass().getName());

    /** The assigner with the highest order wins; vanilla's is 0. */
    default int order(){
        return 0;
    }

    /**
     * {@code assign(player, players)}.
     *
     * @param joining the player being assigned
     * @param seats   vanilla's {@code players}: at a join, {@code Groups.player} (the players already in the world, in
     *                their group order, without {@code joining}); at a reload, every player the reload moves, in its
     *                order, {@code joining} included, each with the team it has at that moment
     * @return a team id, or {@link #NONE}
     */
    int assign(World world, Seat joining, List<Seat> seats);

    /** The assigner in use: the registered one with the highest {@link #ORDER}. */
    static TeamAssigner current(){
        return Holder.CURRENT;
    }

    /**
     * A player as an assigner sees it: vanilla hands it the {@code Player}, whose team, name and admin flag are what
     * assigners read. {@code session} is its identity ({@code other != player}).
     */
    record Seat(long session, String name, boolean admin, int team){
        public Seat withTeam(int team){
            return new Seat(session, name, admin, team);
        }
    }

    /** The assigner {@link #current()} picks among {@code registered}: the highest {@link #ORDER}, else vanilla's. */
    static TeamAssigner pick(List<? extends TeamAssigner> registered){
        TeamAssigner best = null;
        for(TeamAssigner assigner : registered){
            if(best == null || ORDER.compare(assigner, best) > 0) best = assigner;
        }
        return best == null ? new VanillaTeamAssigner() : best;
    }

    final class Holder{
        static final TeamAssigner CURRENT = pick(Registries.instances(TeamAssigner.class));

        private Holder(){}
    }
}
