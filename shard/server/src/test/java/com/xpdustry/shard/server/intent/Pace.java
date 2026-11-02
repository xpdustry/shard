package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.world.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How fast a flooded world ticks next to an unflooded control world, both counted in ticks over the same stretch. The
 * flood tests used to ask for 20 ticks a second of wall time (a third of 60 Hz) after a second of sleep; on a loaded
 * machine both worlds slow down together, so the floor failed with nothing wrong. A ratio against the control world
 * doesn't move with the machine: a flood that makes a tick cost seconds still leaves the flooded world far behind it.
 *
 * <p>The stretch starts with {@link #begin} and ends when the flooded world has run {@link #WINDOW} ticks, or the control
 * world has run so many more that the flooded one can't have kept its {@link #SHARE}. No clock is read; a hang is the only
 * timeout.
 */
final class Pace{
    /** Ticks the flooded world runs for the stretch to be over: a second of play at 60 Hz. */
    static final int WINDOW = 60;
    /** The flooded world must tick at least one in this many of the control world's ticks: the 20 a second of 60 the tests asserted. */
    static final int SHARE = 3;
    /** The control world's state snapshots come every 12 ticks (5 a second at 60 Hz); at least this share of them must arrive. */
    private static final double SNAPSHOT_SHARE = 0.4;

    private final HostedWorld flooded, control;
    private final long floodedBefore, controlBefore;

    private Pace(HostedWorld flooded, HostedWorld control) throws Exception{
        this.flooded = flooded;
        this.control = control;
        floodedBefore = ticks(flooded);
        controlBefore = ticks(control);
    }

    /** Marks the start of the stretch; call it just before the flood. */
    static Pace begin(HostedWorld flooded, HostedWorld control) throws Exception{
        return new Pace(flooded, control);
    }

    static long ticks(HostedWorld world) throws Exception{
        return world.inspect(World::ticks).get(Hang.SECONDS, TimeUnit.SECONDS);
    }

    /** Ticks the flooded world has run since {@link #begin}. */
    long floodedTicks() throws Exception{
        return ticks(flooded) - floodedBefore;
    }

    /** Ticks the control world has run since {@link #begin}. */
    long controlTicks() throws Exception{
        return ticks(control) - controlBefore;
    }

    /** Lets both worlds play until the stretch is over. */
    void play() throws Exception{
        ServerTest.await("the flooded world to run " + WINDOW + " ticks (or fall " + SHARE + " times behind)",
            () -> {
                try{
                    return floodedTicks() >= WINDOW || controlTicks() > (long)SHARE * WINDOW;
                }catch(Exception e){
                    throw new AssertionError(e);
                }
            });
    }

    /**
     * The flooded world kept at least its {@link #SHARE} of the control world's pace, and the control player kept getting
     * state snapshots at their cadence.
     *
     * @param snapshots how many state snapshots the control world's player received during the stretch
     */
    void assertKeptPace(int snapshots) throws Exception{
        long floodedTicks = floodedTicks(), controlTicks = controlTicks();
        String rates = "flooded " + floodedTicks + ", control " + controlTicks + " ticks, " + snapshots + " state snapshots";
        assertTrue(controlTicks >= WINDOW / SHARE, "the control world barely ran: " + rates);
        // unbounded, each tick would read the whole flood: the flooded world ticks a few times while the control runs on
        assertTrue(floodedTicks * SHARE >= controlTicks, rates);
        assertTrue(snapshots >= SNAPSHOT_SHARE * controlTicks / 12, rates);
    }
}
