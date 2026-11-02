package com.xpdustry.shard.compat.v160.pvp;

import com.xpdustry.shard.compat.v160.command.VanillaControl;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.team.Teams;
import com.xpdustry.shard.sim.unit.Unit;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 45's PvP outcome: a three-team PvP game under unmodified scheduling ({@link PvpRun#unpinned}: vanilla with its
 * control pathfinder thread, its formation executor, its physics random and its wall clocks; frames paced at 60 a
 * second; nothing compared frame by frame), where {@code PvpOracleTest} is exact for two teams under
 * {@code CommandRun}'s pins. Sharded (A), crux (B) and malis (C) each have a core; malis and crux have wall fields.
 * <ol>
 *   <li>sharded's player joins alone: paused ({@code pvpAutoPause}) until crux's joins; malis's joins after;</li>
 *   <li>a sharded dagger and a malis dagger are commanded across the map;</li>
 *   <li>while the malis dagger walks its path, malis's core is destroyed: it leaves the active teams that frame, is
 *   destroyed to derelict the next and its units are killed over the next five seconds; the game goes on between two teams;</li>
 *   <li>crux's player leaves: the game waits, paused, until it joins again;</li>
 *   <li>crux's core is destroyed: the game is over, sharded wins, and crux is destroyed to derelict.</li>
 * </ol>
 * Compared, with these tolerances:
 * <ul>
 *   <li>exact, frame for frame: every pause and resume, every change of the active team list, the game over's frame
 *   and winner, and every {@code CoreChangeEvent} (frame, core, team): the edits drive these, not thread timing;</li>
 *   <li>malis's units die within {@value #KILL_WINDOW} frames of its clean-up, on both sides (vanilla's random
 *   0 to 300 frames, plus the {@code LEAVE} to {@code REJOIN} pause, which stops the timers); of its {@value #FIELD}
 *   walls, each side kills between {@value #MIN_KILLED} and {@value #MAX_KILLED} (one in five is scheduled, vanilla's
 *   {@code Mathf.chance(0.2)}) and makes the rest derelict; crux's field ends derelict or killed;</li>
 *   <li>the sharded dagger arrives within {@value #ARRIVE_RADIUS} units of its target on both sides, its arrival
 *   frames at most {@value #ARRIVE_WINDOW} apart.</li>
 * </ul>
 * Each step asserts that it happened, so a game where nothing does can't pass.
 */
class PvpOutcomeOracleTest{
    static final int WIDTH = 96, HEIGHT = 48, FRAMES = 1100, FIELD = 40;
    static final int AX = 10, AY = 24, BX = 85, BY = 38, CX = 85, CY = 10;
    static final int ELIMINATE = 120, LEAVE = 200, REJOIN = 260, END = 750;
    static final int KILL_WINDOW = 302 + (REJOIN - LEAVE), MIN_KILLED = 1, MAX_KILLED = 20, ARRIVE_WINDOW = 120;
    static final float ARRIVE_RADIUS = 24f, KILL = 1e9f;
    static final float SX = 20 * 8f, SY = 6 * 8f, STX = 60 * 8f, STY = 30 * 8f;
    static final float MX = 88 * 8f, MY = 20 * 8f, MTX = 20 * 8f, MTY = 42 * 8f;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void threeTeamsPlayThroughAnEliminationAPauseAndAGameOver(){
        var run = PvpRun.unpinned(WIDTH, HEIGHT);
        run.place("core-shard", AX, AY, Team.sharded);
        run.place("core-shard", BX, BY, Team.crux);
        run.place("core-shard", CX, CY, Team.malis);
        for(int x = 50; x < 70; x++){
            for(int y = 2; y < 4; y++) run.place("copper-wall", x, y, Team.malis);
            run.place("copper-wall", x, 45, Team.crux);
        }
        run.start();
        com.xpdustry.shard.sim.rules.Rules rules = run.shard.part(com.xpdustry.shard.sim.rules.WorldRules.class).rules;
        assertTrue(rules.pvp && rules.pvpAutoPause && !rules.coreCapture && rules.cleanupDeadTeams, "the rules came through the save");
        assertEquals(Set.of(1, 2, 3), new HashSet<>(activeVanilla()), "three active teams: " + activeVanilla());
        assertEquals(activeVanilla(), activeShard(run), "the same active list after the load");
        run.coreChanges();

        int sd = run.commanded("dagger", Team.sharded, SX, SY);
        int md = run.commanded("dagger", Team.malis, MX, MY);
        int cd = run.unit("dagger", Team.crux, 70 * 8f, 36 * 8f);

        run.at(1, () -> run.join(Team.sharded));
        run.at(40, () -> run.join(Team.crux));
        run.at(45, () -> run.join(Team.malis));
        run.at(50, () -> {
            run.move(Team.sharded, STX, STY, sd);
            run.move(Team.malis, MTX, MTY, md);
        });
        run.at(ELIMINATE, () -> run.damage(CX, CY, Team.sharded, KILL));
        run.at(LEAVE, () -> run.leave(Team.crux));
        run.at(REJOIN, () -> run.join(Team.crux));
        run.at(END, () -> run.damage(BX, BY, Team.sharded, KILL));

        // each side's transitions, frame by frame; and what the outcome reads
        var vanillaLog = new ArrayList<String>();
        var shardLog = new ArrayList<String>();
        boolean[] lastPaused = {false, false};
        List<?>[] lastActive = {activeVanilla(), activeShard(run)};
        int[] malisDead = {-1, -1}, arrived = {-1, -1};
        float[][] walking = new float[2][];
        run.after = frame -> {
            boolean vp = state.isPaused(), sp = run.shard.paused();
            if(vp != lastPaused[0]) vanillaLog.add(frame + (vp ? " paused" : " resumed"));
            if(sp != lastPaused[1]) shardLog.add(frame + (sp ? " paused" : " resumed"));
            lastPaused[0] = vp;
            lastPaused[1] = sp;
            List<Integer> va = activeVanilla(), sa = activeShard(run);
            if(!va.equals(lastActive[0])) vanillaLog.add(frame + " active " + va);
            if(!sa.equals(lastActive[1])) shardLog.add(frame + " active " + sa);
            lastActive[0] = va;
            lastActive[1] = sa;
            if(frame == ELIMINATE){
                var theirs = run.vanillaUnit(md);
                Unit ours = run.shardUnit(md);
                walking[0] = new float[]{theirs.x, theirs.y};
                walking[1] = new float[]{ours.x, ours.y};
            }
            if(malisDead[0] < 0 && frame > ELIMINATE && noUnits(Team.malis)) malisDead[0] = frame;
            if(malisDead[1] < 0 && frame > ELIMINATE && noShardUnits(run, Team.malis.id)) malisDead[1] = frame;
            var theirs = run.vanillaUnit(sd);
            Unit ours = run.shardUnit(sd);
            if(arrived[0] < 0 && theirs != null && theirs.within(STX, STY, ARRIVE_RADIUS)) arrived[0] = frame;
            if(arrived[1] < 0 && ours != null && Math.hypot(ours.x - STX, ours.y - STY) <= ARRIVE_RADIUS) arrived[1] = frame;
        };
        run.runTo(FRAMES);

        System.out.println("vanilla: " + vanillaLog + " overs " + run.vanillaOvers + " core changes " + run.vanillaCoreChanges);
        System.out.println("shard:   " + shardLog + " overs " + run.shardOvers + " core changes " + run.shardCoreChanges);
        System.out.println("malis dead at " + Arrays.toString(malisDead) + ", sharded dagger arrived at " + Arrays.toString(arrived)
            + ", malis dagger at the elimination " + Arrays.toString(walking[0]) + " / " + Arrays.toString(walking[1]));
        assertTrue(VanillaControl.threadAlive(), "vanilla's control pathfinder ran on its thread");

        // the transitions, exact
        assertEquals(vanillaLog, shardLog, "pauses, resumes and the active teams, frame by frame");
        assertEquals(run.vanillaOvers, run.shardOvers, "the game over");
        assertEquals(run.vanillaCoreChanges, run.shardCoreChanges, "CoreChangeEvent, frame by frame");
        // ... and that they are the game described
        assertTrue(vanillaLog.contains("1 paused") && vanillaLog.contains("40 resumed"), "waited for crux's player: " + vanillaLog);
        assertTrue(vanillaLog.contains(LEAVE + " paused") && vanillaLog.contains(REJOIN + " resumed"), "waited for crux's player again: " + vanillaLog);
        assertTrue(vanillaLog.stream().anyMatch(line -> line.startsWith(ELIMINATE + " active ") && !line.contains("3")), "malis left the active teams: " + vanillaLog);
        assertEquals(List.of(END + " " + Team.sharded.id), run.vanillaOvers, "sharded won when crux's core fell");
        String malisCore = ELIMINATE + " " + arc.math.geom.Point2.pack(CX, CY) + " t" + Team.malis.id;
        assertTrue(run.vanillaCoreChanges.contains(malisCore), "malis's core fired CoreChangeEvent: " + run.vanillaCoreChanges);

        // malis: eliminated while its commanded dagger walked its path
        for(float[] at : walking){
            assertNotNull(at, "the malis dagger was alive at the elimination");
            assertTrue(Math.hypot(at[0] - MX, at[1] - MY) > 8f && Math.hypot(at[0] - MTX, at[1] - MTY) > 100f,
                "the malis dagger was on its way: " + Arrays.toString(at));
        }
        for(int side = 0; side < 2; side++){
            assertTrue(malisDead[side] > ELIMINATE && malisDead[side] <= ELIMINATE + 1 + KILL_WINDOW, "malis's units died in time: " + Arrays.toString(malisDead));
        }
        int[] vanillaField = field(run::vanillaBlock, 2, Team.malis.id), shardField = field(run::shardBlock, 2, Team.malis.id);
        System.out.println("malis's field killed/derelict: vanilla " + Arrays.toString(vanillaField) + ", Shard " + Arrays.toString(shardField));
        for(int[] counts : new int[][]{vanillaField, shardField}){
            assertEquals(FIELD, counts[0] + counts[1], "malis's field is killed or derelict: " + Arrays.toString(counts));
            assertTrue(counts[0] >= MIN_KILLED && counts[0] <= MAX_KILLED, "malis's field, killed: " + Arrays.toString(counts));
        }
        for(int x = 50; x < 70; x++){
            for(var block : List.of(run.vanillaBlock(x, 45), run.shardBlock(x, 45))){
                assertTrue(block.equals("air") || block.equals("copper-wall t0"), "crux's wall at " + x + ": " + block);
            }
        }
        assertNull(run.vanillaUnit(cd), "crux's dagger was killed");
        assertNull(run.shardUnit(cd), "crux's dagger was killed");

        // the sharded dagger, across the pause
        assertTrue(arrived[0] > 0 && arrived[1] > 0, "the sharded dagger arrived: " + Arrays.toString(arrived));
        assertTrue(Math.abs(arrived[0] - arrived[1]) <= ARRIVE_WINDOW, "its arrival frames: " + Arrays.toString(arrived));
    }

    static List<Integer> activeVanilla(){
        var out = new ArrayList<Integer>();
        // the raw list: getActive() would drop teams as it reads
        for(var data : state.teams.active) out.add(data.team.id);
        return out;
    }

    static List<Integer> activeShard(PvpRun run){
        var out = new ArrayList<Integer>();
        var list = Teams.of(run.shard).activeTeams();
        for(int i = 0; i < list.size; i++) out.add(list.get(i));
        return out;
    }

    static boolean noUnits(Team team){
        for(mindustry.gen.Unit u : mindustry.gen.Groups.unit) if(u.team == team && !u.dead) return false;
        return true;
    }

    static boolean noShardUnits(PvpRun run, int team){
        for(int i = 0; i < run.shard.units().size(); i++){
            Unit u = run.shard.units().get(i);
            if(u.team == team && !u.dead) return false;
        }
        return true;
    }

    /** The field at rows {@code y} and {@code y + 1} of x 50 to 69: {killed, derelict}, failing on a wall still {@code team}'s. */
    static int[] field(java.util.function.BiFunction<Integer, Integer, String> block, int y, int team){
        int killed = 0, derelict = 0;
        for(int x = 50; x < 70; x++){
            for(int row = y; row < y + 2; row++){
                String b = block.apply(x, row);
                if(b.equals("air")) killed++;
                else if(b.equals("copper-wall t0")) derelict++;
                else fail("(" + x + ", " + row + ") is " + b + ", not killed or derelict");
            }
        }
        return new int[]{killed, derelict};
    }
}
