package com.xpdustry.shard.server.host;

import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * What sessions do to a hosted world, as commands and intents the world logs and admits, and the events its host
 * reacts to on the tick thread. Membership and the session's {@link Player} change in one command, so a full inbox
 * can't split them.
 */
public final class HostInputs{
    /**
     * The session's route starts here: it becomes a member and gets a new player, as vanilla's connect handler makes
     * one ({@code NetServer.java:280}): {@code Player.create()}, the name, the color made opaque, the admin flag and
     * the team ({@link #team}). The player isn't added until the client confirms the world load.
     *
     * @param color  RGBA8888 as the client sent it
     * @param reload the players a map reload moves here, in the old world's {@code Groups.player} order with their old
     *               teams ({@code WorldReloader.begin}), or null for a plain join
     */
    public record Join(long session, int generation, String name, int color, boolean admin, List<TeamAssigner.Seat> reload) implements Command.Internal{
        public Join{
            reload = reload == null ? null : List.copyOf(reload);
        }

        public Join(long session, int generation, String name, int color, boolean admin){
            this(session, generation, name, color, admin, null);
        }

        @Override
        public Verdict apply(World world, Cause cause){
            Verdict joined = new Members.Join(session, generation).apply(world, cause);
            if(!joined.admitted()) return joined;
            Roster roster = Roster.of(world);
            // a newer route of a session the world still lists: its leave was lost to a failure
            Player stale = roster.remove(session);
            if(stale != null) stale.remove();
            Player player = Player.create(world);
            player.name = name;
            player.color = color | 0xff;
            player.admin = admin;
            player.team(team(world, roster));
            roster.put(session, player);
            world.publish(new Joined(session, generation, player.id()));
            return Verdict.ADMITTED;
        }

        /**
         * The joining player's team. A plain join is vanilla's connect ({@code NetServer.java:318}):
         * {@code assignTeam(player)} over {@code Groups.player}, the players already added here. A join a reload
         * moved here is {@code WorldReloader.end}: outside PvP {@code p.reset()}'s default team; in PvP the reload's
         * whole list is assigned at its first join, in its order ({@link #reloadTeams}). Vanilla's {@code null}
         * ({@link TeamAssigner#NONE}) would leave the player without a team, and its next {@code Player.update} throws
         * ({@code bestCore()} reads {@code team.cores()}, PlayerComp.java:122); Shard gives it the default team.
         */
        int team(World world, Roster roster){
            Rules rules = world.part(WorldRules.class).rules;
            int team;
            if(reload != null && reloaded(session)){
                if(!rules.pvp) return rules.defaultTeam;
                if(!roster.hasReloadTeam(session)) roster.reloadTeams(reloadTeams(world, reload));
                team = roster.takeReloadTeam(session);
            }else{
                team = TeamAssigner.current().assign(world, new TeamAssigner.Seat(session, name, admin, rules.defaultTeam), seats(world, roster));
            }
            return team == TeamAssigner.NONE ? rules.defaultTeam : team;
        }

        private boolean reloaded(long session){
            for(TeamAssigner.Seat seat : reload){
                if(seat.session() == session) return true;
            }
            return false;
        }

        /**
         * {@code WorldReloader.end} (WorldReloader.java:47-59) in PvP: for each player in order, {@code p.reset()} sets
         * its team to the default one, then {@code assignTeam(p, players)} counts over the whole list. Vanilla quirk:
         * the players after it haven't been reset yet, so they count with the team they had on the old map.
         */
        static SortedMap<Long, Integer> reloadTeams(World world, List<TeamAssigner.Seat> reload){
            Rules rules = world.part(WorldRules.class).rules;
            TeamAssigner assigner = TeamAssigner.current();
            var seats = new ArrayList<>(reload);
            var out = new TreeMap<Long, Integer>();
            for(int i = 0; i < seats.size(); i++){
                TeamAssigner.Seat seat = seats.get(i).withTeam(rules.defaultTeam);
                seats.set(i, seat);
                int team = assigner.assign(world, seat, Collections.unmodifiableList(seats));
                seats.set(i, seat.withTeam(team));
                out.put(seat.session(), team);
            }
            return out;
        }

        /** {@code Groups.player} as seats: the added players in group order, each with its session (-1 if none). */
        static List<TeamAssigner.Seat> seats(World world, Roster roster){
            Players players = Players.of(world);
            if(players == null) return List.of();
            var sessions = new IdentityHashMap<Player, Long>();
            roster.players().forEach((session, player) -> sessions.put(player, session));
            var out = new ArrayList<TeamAssigner.Seat>(players.list().size());
            for(int i = 0; i < players.list().size(); i++){
                Player other = players.list().get(i);
                out.add(new TeamAssigner.Seat(sessions.getOrDefault(other, -1L), other.name, other.admin, other.team));
            }
            return out;
        }
    }

    /**
     * The session's route here ends: vanilla's {@code onDisconnect} ({@code NetServer.java:609}). A unit the core
     * spawned for the player despawns; the player is removed.
     */
    public record Leave(long session) implements Command.Internal{
        @Override
        public Verdict apply(World world, Cause cause){
            Verdict left = new Members.Leave(session).apply(world, cause);
            if(!left.admitted()) return left;
            Player player = Roster.of(world).remove(session);
            if(player == null) return Verdict.ADMITTED;
            boolean confirmed = player.isAdded();
            Unit unit = player.unit();
            if(unit != null && unit.spawnedByCore && !unit.dead) unit.despawn();
            player.remove();
            world.publish(new Left(session, player.id(), confirmed));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code connectConfirm}: the client loaded the world ({@code NetServer.java:999}). The player is added, and, on
     * its first confirm here, its death timer is set so the next update asks {@link PlayerSpawns} for a unit at once,
     * as vanilla respawns a connecting player instantly. Until WP23 spawns players at cores, nothing answers and the
     * player stays dead. A player already added confirms a {@code /sync}'s reload: nothing changes in the world, and
     * the event tells the host the client has the world again.
     *
     * @param load the session's load generation it confirms ({@code Session.loadGeneration()})
     */
    public record Confirm(int load) implements Intent{
        @Override
        public Verdict apply(World world, IntentEnvelope envelope){
            Player player = Roster.of(world).player(envelope.session());
            if(player == null) return Verdict.rejected("no player");
            if(player.isAdded()){
                world.publish(new Confirmed(envelope.session(), player.id(), load));
                return Verdict.ADMITTED;
            }
            player.add();
            // vanilla's order: the join is announced, then the core's playerSpawn call goes to everyone, the player too
            world.publish(new Confirmed(envelope.session(), player.id(), load));
            player.deathTimer = Player.DEATH_DELAY;
            player.update();
            return Verdict.ADMITTED;
        }
    }

    /** A {@link Join} was admitted: the host sends the world. */
    public record Joined(long session, int generation, int player) implements WorldEvent{
    }

    /** A {@link Leave} was admitted. {@code confirmed}: the player had been added, so clients know it. */
    public record Left(long session, int player, boolean confirmed) implements WorldEvent{
    }

    /** A {@link Confirm} was admitted: the player is in the world, and the client has loaded it ({@code load}). */
    public record Confirmed(long session, int player, int load) implements WorldEvent{
    }

    private HostInputs(){}
}
