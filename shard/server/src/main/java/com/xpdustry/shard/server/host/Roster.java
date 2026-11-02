package com.xpdustry.shard.server.host;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Which {@link Player} each session has in this world, by session id: vanilla's {@code NetConnection.player}, kept as
 * world state so a fork, a checkpoint and a replay know which player a session's intents act on. A player is here
 * from its session's join (created, not yet added) to its leave; {@link Players} lists only the added ones.
 *
 * <p>Not registered: only hosted worlds run it ({@link HostedSystems}), so worlds built elsewhere hash as before.
 */
public final class Roster implements WorldPart, Checkpointable{
    private final TreeMap<Long, Player> players = new TreeMap<>();
    @Derived("a read-only view of players")
    private final SortedMap<Long, Player> view = Collections.unmodifiableSortedMap(players);
    /**
     * The teams a PvP map reload assigned ({@code WorldReloader.end}), by session, until each session's join takes its
     * own: the whole list is assigned at the reload's first join, in vanilla's order ({@link HostInputs.Join#team}).
     */
    private final TreeMap<Long, Integer> reloadTeams = new TreeMap<>();

    public Roster(){
    }

    /** The world's roster, or null if it doesn't run one. */
    public static Roster of(World world){
        return world.find(Roster.class);
    }

    /** The session's player, or null. */
    public Player player(long session){
        return players.get(session);
    }

    /** Every session's player, by ascending session id. */
    public SortedMap<Long, Player> players(){
        return view;
    }

    public int size(){
        return players.size();
    }

    void put(long session, Player player){
        players.put(session, Objects.requireNonNull(player, "player"));
    }

    Player remove(long session){
        return players.remove(session);
    }

    boolean hasReloadTeam(long session){
        return reloadTeams.containsKey(session);
    }

    void reloadTeams(Map<Long, Integer> teams){
        reloadTeams.putAll(teams);
    }

    int takeReloadTeam(long session){
        return reloadTeams.remove(session);
    }

    /** The reload's teams not taken yet, by session; for tests. */
    SortedMap<Long, Integer> reloadTeams(){
        return Collections.unmodifiableSortedMap(reloadTeams);
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new Roster();
        players.forEach((session, player) -> out.players.put(session, context.map(player)));
        out.reloadTeams.putAll(reloadTeams);
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(players.size());
        players.forEach((session, player) -> {
            hasher.l(session).bool(player.isAdded());
            player.hashState(hasher);
        });
        // only a reload in progress enters the hash, which other worlds keep as they were
        if(!reloadTeams.isEmpty()){
            hasher.i(reloadTeams.size());
            reloadTeams.forEach((session, team) -> hasher.l(session).i(team));
        }
    }

    @Override
    public void write(CheckpointOut out){
        long[] sessions = new long[players.size()];
        var refs = new Copyable[players.size()];
        int i = 0;
        for(var entry : players.entrySet()){
            sessions[i] = entry.getKey();
            refs[i++] = entry.getValue();
        }
        out.longs("sessions", sessions).refs("players", refs, refs.length);
        long[] reloaded = new long[reloadTeams.size()];
        int[] teams = new int[reloadTeams.size()];
        i = 0;
        for(var entry : reloadTeams.entrySet()){
            reloaded[i] = entry.getKey();
            teams[i++] = entry.getValue();
        }
        out.longs("reloadSessions", reloaded).ints("reloadTeams", teams, teams.length);
    }

    @Override
    public void read(CheckpointIn in){
        long[] sessions = in.longs("sessions");
        Player[] refs = in.refs("players", Player.class);
        if(sessions.length != refs.length) throw new CheckpointException("the roster has " + sessions.length + " sessions for " + refs.length + " players");
        players.clear();
        for(int i = 0; i < sessions.length; i++){
            if(refs[i] == null) throw new CheckpointException("session " + sessions[i] + " has no player");
            players.put(sessions[i], refs[i]);
        }
        reloadTeams.clear();
        long[] reloaded = in.longs("reloadSessions");
        int[] teams = in.ints("reloadTeams");
        if(reloaded == null && teams == null) return;
        if(reloaded == null || teams == null || reloaded.length != teams.length) throw new CheckpointException("the roster's reload teams are inconsistent");
        for(int r = 0; r < reloaded.length; r++) reloadTeams.put(reloaded[r], teams[r]);
    }
}
