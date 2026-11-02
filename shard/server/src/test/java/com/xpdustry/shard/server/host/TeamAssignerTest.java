package com.xpdustry.shard.server.host;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.host.HostInputs.*;
import com.xpdustry.shard.server.host.TeamAssigner.Seat;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP83: vanilla's {@code assignTeam} (NetServer.java:57-75), one test per case, and {@code WorldReloader.end}'s order
 * on a PvP map reload (WorldReloader.java:47-59). Each expected tie-break replays vanilla's draws on a copy of the world
 * random: one {@code Mathf.random(-0.1f, 0.1f)} per qualifying active team, in active order, {@code <=} keeping the
 * last of equal values.
 */
class TeamAssignerTest{
    static final int SHARDED = Team.SHARDED, CRUX = Team.CRUX, MALIS = Team.MALIS, BLUE = 5;
    final VanillaTeamAssigner vanilla = new VanillaTeamAssigner();
    World world;
    Rules rules;
    long sequence;

    @BeforeEach
    void world(){
        WorldConfig template = HostedSystems.template(Epoch.vanilla(), 3, 256);
        world = new World(new WorldConfig(template.content(), 40, 40, 3, template.systems(), template.kinds(), template.inboxCapacity(), template.journal()));
        rules = world.part(WorldRules.class).rules;
        rules.waves = false;
        rules.pvp = true;
    }

    void core(int team, int x, int y){
        world.buildings().setBlock(x, y, world.content().block("core-shard"), team, 0, Cause.SERVER);
    }

    /** Only a core lists a team as active (vanilla never calls {@code updateActive}); a building keeps it listed. */
    void coreLostKeepingAWall(int team, int x, int y){
        core(team, x, y);
        world.buildings().setBlock(x + 3, y, world.content().block("copper-wall"), team, 0, Cause.SERVER);
        world.buildings().setBlock(x, y, world.content().block("air"), 0, 0, Cause.SERVER);
        assertTrue(Teams.of(world).cores(team).isEmpty());
    }

    static Seat seat(long session, int team){
        return new Seat(session, "p" + session, false, team);
    }

    static final Seat JOINING = seat(99, SHARDED);

    /** Vanilla's draws for {@code counts} (one per active team in order; null for a team that doesn't qualify). */
    int expected(Integer... counts){
        Rand rand = world.rand().copy();
        int[] active = Teams.of(world).getActive(world).toArray();
        int best = TeamAssigner.NONE;
        float min = Float.MAX_VALUE;
        for(int i = 0; i < counts.length; i++){
            float value = counts[i] == null ? Integer.MAX_VALUE : counts[i] + Mathf.random(rand, -0.1f, 0.1f);
            if(value <= min){
                best = active[i];
                min = value;
            }
        }
        return best;
    }

    @Test
    void outsidePvpTheDefaultTeam(){
        rules.pvp = false;
        rules.defaultTeam = BLUE;
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        long state = world.rand().getState(0);
        assertEquals(BLUE, vanilla.assign(world, JOINING, List.of(seat(1, SHARDED))));
        assertEquals(state, world.rand().getState(0), "no draw outside PvP");
    }

    @Test
    void theTeamWithFewerPlayers(){
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        assertArrayEquals(new int[]{CRUX, SHARDED}, Teams.of(world).getActive(world).toArray(), "crux first, as vanilla's constructor lists it");
        var seats = List.of(seat(1, CRUX), seat(2, CRUX), seat(3, SHARDED));
        int expected = expected(2, 1);
        assertEquals(SHARDED, expected);
        assertEquals(expected, vanilla.assign(world, JOINING, seats));
    }

    @Test
    void theJoiningPlayerIsNotCounted(){
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        // at a reload the list holds the player itself: on crux it doesn't count for crux
        Seat self = seat(1, CRUX);
        var seats = List.of(self, seat(2, SHARDED));
        int expected = expected(0, 1);
        assertEquals(CRUX, expected);
        assertEquals(expected, vanilla.assign(world, self, seats));
    }

    @Test
    void aTieDrawsFromTheWorldRandom(){
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        core(MALIS, 5, 30);
        var seen = new TreeSet<Integer>();
        for(int i = 0; i < 40; i++){
            int expected = expected(0, 0, 0);
            Rand before = world.rand().copy();
            int team = vanilla.assign(world, JOINING, List.of());
            assertEquals(expected, team, "draw " + i);
            // exactly three draws
            for(int d = 0; d < 3; d++) Mathf.random(before, -0.1f, 0.1f);
            assertEquals(before.getState(0), world.rand().getState(0));
            seen.add(team);
        }
        assertEquals(Set.of(SHARDED, CRUX, MALIS), seen, "every tied team gets picked sometimes");
    }

    @Test
    void theWaveTeamDoesNotQualifyWhileWavesAreOn(){
        rules.waves = true;
        rules.waveTeam = CRUX;
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        // crux has two fewer players but is the wave team
        int expected = expected(null, 2);
        assertEquals(SHARDED, expected);
        assertEquals(SHARDED, vanilla.assign(world, JOINING, List.of(seat(1, SHARDED), seat(2, SHARDED))));
        rules.waves = false;
        assertEquals(expected(0, 2), vanilla.assign(world, JOINING, List.of(seat(1, SHARDED), seat(2, SHARDED))));
    }

    @Test
    void aTeamWithoutCoresOrProtectionDoesNotQualify(){
        core(SHARDED, 5, 5);
        // malis lost its core, and stays active through a wall
        coreLostKeepingAWall(MALIS, 30, 30);
        core(BLUE, 5, 30);
        rules.teams.get(BLUE).protectCores = false;
        assertArrayEquals(new int[]{SHARDED, MALIS, BLUE}, Teams.of(world).getActive(world).toArray());
        var seats = List.of(seat(1, SHARDED), seat(2, SHARDED), seat(3, SHARDED));
        assertEquals(expected(3, null, null), SHARDED);
        assertEquals(SHARDED, vanilla.assign(world, JOINING, seats));
    }

    @Test
    void derelictDoesNotQualify(){
        core(Team.DERELICT, 30, 30);
        core(SHARDED, 5, 5);
        int[] active = Teams.of(world).getActive(world).toArray();
        assertEquals(List.of(Team.DERELICT, SHARDED), List.of(active[0], active[1]));
        assertEquals(expected(null, 5), vanilla.assign(world, JOINING, List.of(seat(1, SHARDED), seat(2, SHARDED), seat(3, SHARDED), seat(4, SHARDED), seat(5, SHARDED))));
    }

    /** Vanilla quirk: with every active team disqualified, {@code Seq.min} keeps the last ({@code <=}), not null. */
    @Test
    void withNoTeamQualifyingTheLastActiveTeam(){
        coreLostKeepingAWall(SHARDED, 5, 5);
        coreLostKeepingAWall(MALIS, 30, 30);
        assertArrayEquals(new int[]{SHARDED, MALIS}, Teams.of(world).getActive(world).toArray());
        long state = world.rand().getState(0);
        assertEquals(MALIS, vanilla.assign(world, JOINING, List.of()));
        assertEquals(state, world.rand().getState(0), "no team qualified: no draw");
    }

    /** Vanilla's {@code null}: no active team at all. The join falls back to the default team. */
    @Test
    void withNoActiveTeamNoneAndTheJoinTakesTheDefaultTeam(){
        rules.defaultTeam = BLUE;
        assertEquals(0, Teams.of(world).getActive(world).size);
        assertEquals(TeamAssigner.NONE, vanilla.assign(world, JOINING, List.of()));
        submit(new Join(7, 1, "alpha", -1, false));
        world.tick();
        assertEquals(BLUE, Roster.of(world).player(7).team);
    }

    @Test
    void aJoinCountsTheAddedPlayersOnly(){
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        // two players join and confirm, a third joins but never confirms
        join(1, true);
        join(2, true);
        join(3, false);
        var teams = Roster.of(world).players();
        int a = teams.get(1L).team, b = teams.get(2L).team, c = teams.get(3L).team;
        assertNotEquals(a, b, "the second player joins the empty team");
        // the fourth counts the two added players, not the third
        int expected = expected(count(CRUX), count(SHARDED));
        join(4, false);
        assertEquals(expected, teams.get(4L).team, "the unconfirmed third player isn't in Groups.player");
        assertTrue(c == SHARDED || c == CRUX);
    }

    int count(int team){
        int n = 0;
        var players = com.xpdustry.shard.sim.unit.Players.of(world).list();
        for(int i = 0; i < players.size(); i++) if(players.get(i).team == team) n++;
        return n;
    }

    void join(long session, boolean confirm){
        submit(new Join(session, 1, "p" + session, -1, false));
        world.tick();
        if(confirm){
            assertTrue(world.inbox().offer(new IntentEnvelope(session, 1, sequence++, 0, new Confirm(1))));
            world.tick();
        }
    }

    void submit(Command command){
        assertTrue(world.inbox().offer(new Submission(command, Cause.SERVER)));
    }

    /**
     * {@code WorldReloader.end}: each player in the old order is reset to the default team and assigned, counting the
     * players before it with their new teams and the ones after it with their old map's teams (vanilla quirk).
     */
    @Test
    void aReloadAssignsInTheOldOrderCountingOldTeams(){
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        // on the old map all four were on crux
        var reload = List.of(seat(10, CRUX), seat(11, CRUX), seat(12, CRUX), seat(13, CRUX));
        var expected = new TreeMap<Long, Integer>();
        Rand saved = world.rand().copy();
        var seats = new ArrayList<>(reload);
        for(int i = 0; i < seats.size(); i++){
            int[] counts = new int[2];
            for(int j = 0; j < seats.size(); j++){
                if(j == i) continue;
                if(seats.get(j).team() == CRUX) counts[0]++;
                if(seats.get(j).team() == SHARDED) counts[1]++;
            }
            int team = expected(counts[0], counts[1]);
            // advance the world random as the assigner will
            Mathf.random(world.rand(), -0.1f, 0.1f);
            Mathf.random(world.rand(), -0.1f, 0.1f);
            seats.set(i, seats.get(i).withTeam(team));
            expected.put(seats.get(i).session(), team);
        }
        world.rand().setState(saved.getState(0), saved.getState(1));
        // the first sees three on crux: sharded; the second two on crux, one on sharded: sharded again
        assertEquals(List.of(SHARDED, SHARDED), List.of(expected.get(10L), expected.get(11L)));

        // the joins arrive in another order than the list: the list's order still decides
        for(long session : new long[]{12, 10, 13, 11}) submit(new Join(session, 1, "p" + session, -1, false, reload));
        world.tick();
        var players = Roster.of(world).players();
        for(long session = 10; session <= 13; session++) assertEquals(expected.get(session), players.get(session).team, "session " + session);
        assertTrue(Roster.of(world).reloadTeams().isEmpty(), "every join took its team");
    }

    @Test
    void aReloadOutsidePvpGivesTheDefaultTeam(){
        rules.pvp = false;
        rules.defaultTeam = BLUE;
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        long state = world.rand().getState(0);
        submit(new Join(10, 1, "p10", -1, false, List.of(seat(10, CRUX), seat(11, SHARDED))));
        world.tick();
        assertEquals(BLUE, Roster.of(world).player(10).team);
        assertEquals(state, world.rand().getState(0));
    }

    @Test
    void aSessionNotInTheReloadJoinsPlainly(){
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        int expected = expected(0, 0);
        submit(new Join(50, 1, "late", -1, false, List.of(seat(10, CRUX))));
        world.tick();
        assertEquals(expected, Roster.of(world).player(50).team);
        assertTrue(Roster.of(world).reloadTeams().isEmpty(), "the reload wasn't assigned: none of its players joined");
    }

    @Test
    void aReloadInProgressSurvivesACheckpointAndAFork(){
        core(SHARDED, 5, 5);
        core(CRUX, 30, 30);
        var reload = List.of(seat(10, CRUX), seat(11, CRUX), seat(12, SHARDED));
        submit(new Join(10, 1, "p10", -1, false, reload));
        world.tick();
        SortedMap<Long, Integer> pending = new TreeMap<>(Roster.of(world).reloadTeams());
        assertEquals(Set.of(11L, 12L), pending.keySet());
        WorldHash hash = world.hash();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        World fork = world.fork();
        assertEquals(hash, restored.hash());
        assertEquals(hash, fork.hash());
        for(World w : List.of(world, restored, fork)){
            assertEquals(pending, Roster.of(w).reloadTeams());
            assertTrue(w.inbox().offer(new Submission(new Join(11, 1, "p11", -1, false, reload), Cause.SERVER)));
            w.tick();
            assertEquals(pending.get(11L), Roster.of(w).player(11).team);
        }
        assertEquals(world.hash(), restored.hash());
        assertEquals(world.hash(), fork.hash());
    }

    @Test
    void theHighestOrderedAssignerIsUsed(){
        assertInstanceOf(VanillaTeamAssigner.class, TeamAssigner.current());
        TeamAssigner plugin = new TeamAssigner(){
            @Override
            public int order(){
                return 10;
            }

            @Override
            public int assign(World world, Seat joining, List<Seat> seats){
                return BLUE;
            }
        };
        assertSame(plugin, TeamAssigner.pick(List.of(vanilla, plugin)));
        assertSame(plugin, TeamAssigner.pick(List.of(plugin, vanilla)));
        assertInstanceOf(VanillaTeamAssigner.class, TeamAssigner.pick(List.of()));
    }
}
