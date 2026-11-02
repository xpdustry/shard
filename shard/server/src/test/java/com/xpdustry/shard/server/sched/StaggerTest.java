package com.xpdustry.shard.server.sched;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Stagger} and {@link Histogram}. */
class StaggerTest{
    /** The largest number of worlds that share one of {@code buckets} buckets. */
    static int crowd(double[] fractions, int buckets){
        var counts = new int[buckets];
        for(double fraction : fractions) counts[(int)(fraction * buckets)]++;
        return Arrays.stream(counts).max().orElse(0);
    }

    @Test
    void phasesOfNeighbouringIdsSpreadOut(){
        int worlds = 200, buckets = 12;
        var mixed = new double[worlds];
        var naive = new double[worlds];
        for(int id = 0; id < worlds; id++){
            mixed[id] = Stagger.snapshotPhase(id, 12_000) / 12_000.0;
            naive[id] = new Random(id).nextDouble();
        }
        // The bug note 24 hit: java.util.Random seeded 0, 1, 2… gives nearly the same first double.
        assertTrue(crowd(naive, buckets) > worlds / 2, "the naive seeding should crowd: " + crowd(naive, buckets));
        // Mixed: close to uniform (200 / 12 ≈ 17 per bucket).
        assertTrue(crowd(mixed, buckets) <= 30, "mixed phases crowd: " + crowd(mixed, buckets));
    }

    @Test
    void snapshotTicksAreSpreadAcrossTheInterval(){
        int interval = 12;
        var perTick = new int[interval];
        for(long id = 0; id < 240; id++){
            int due = 0;
            for(long tick = 1000; tick < 1000 + interval; tick++){
                if(Stagger.snapshotDue(id, tick, interval)){
                    perTick[(int)(tick % interval)]++;
                    due++;
                }
            }
            assertEquals(1, due, "each world snapshots once per interval");
        }
        int max = Arrays.stream(perTick).max().orElseThrow();
        assertTrue(max <= 35, "about 20 worlds per tick, not all on one: " + Arrays.toString(perTick));
    }

    @Test
    void tickAndSnapshotPhasesAreIndependent(){
        int same = 0;
        for(long id = 0; id < 1000; id++){
            int tick = (int)(Stagger.tickPhase(id, 12_000) / 1000);
            int snapshot = Stagger.snapshotPhase(id, 12);
            if(tick == snapshot) same++;
        }
        assertTrue(same < 150, "phases should not track each other: " + same + " of 1000 equal (chance: ~83)");
    }

    @Test
    void histogramQuantilesAreWithinABucket(){
        var histogram = new Histogram();
        for(int i = 1; i <= 1000; i++) histogram.record(i);
        assertEquals(1000, histogram.count());
        long p50 = histogram.quantile(0.5), p99 = histogram.quantile(0.99);
        assertTrue(p50 >= 500 && p50 <= 563, "p50 " + p50);
        assertTrue(p99 >= 990 && p99 <= 1000, "p99 " + p99);
        assertEquals(1000, histogram.max());
        Histogram drained = histogram.drain();
        assertEquals(0, histogram.count());
        assertEquals(1000, drained.count());
        histogram.merge(drained);
        assertEquals(1000, histogram.count());
    }
}
