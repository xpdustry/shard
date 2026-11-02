package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.compat.v160.blocks.transport.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.bullet.*;
import arc.util.io.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP33): mass drivers shoot, receive and hand on items as vanilla's do. {@link BlockRun} compares every building
 * after every frame (link, turret angle, reload, state, waiting shooters, items), every item the voids take, and the
 * extra lines here: the random (each shot and catch draws a sound pitch, each dropped item a value), the entity id
 * counter, and every bullet with the items it carries. At the end, the save chunks and each driver's block-snapshot
 * body ({@code writeSync}). Then, with a bolt in flight, vanilla saves; both load that save from entity id 0, drop the
 * unsaved bolt and run on for {@link #AFTER_LOAD} frames, ids compared as absolute numbers throughout.
 */
class MassDriverOracleTest{
    static final int FRAMES = 1800;
    /** Frames both run on after loading vanilla's save of the fixture. */
    static final int AFTER_LOAD = 600;

    @BeforeAll
    static void recordVoids(){
        BlockRun.recordVoids();
    }

    @AfterAll
    static void restoreVoids(){
        BlockRun.restoreVoids();
    }

    /**
     * Three drivers across a 64 × 40 map, each with a power source beside it. A (6, 6) and C (26, 30) are fed by
     * titanium conveyors from item sources; B (46, 6) dumps into a void, C into another while it isn't shooting. A and
     * C both shoot at B, so they queue in its waiting shooters. At 900 A relinks to C (by a relative {@code Point2}),
     * which then receives from A while it waits on B; at 1300 C unlinks ({@code -1}) and only receives. A loses power
     * from 1450 to 1650.
     */
    @Test
    void threeDriversRelink(){
        var run = new BlockRun(64, 40);
        run.place("router", 63, 39);
        run.place("item-source", 2, 6).line("titanium-conveyor", 3, 6, 0, 2).place("mass-driver", 6, 6).place("power-source", 6, 8);
        run.place("mass-driver", 46, 6).place("power-source", 46, 8).place("item-void", 48, 6);
        run.place("item-source", 22, 30).line("titanium-conveyor", 23, 30, 0, 2).place("mass-driver", 26, 30).place("power-source", 26, 32)
            .place("item-void", 28, 30);
        run.configure(2, 6, "copper").configure(22, 30, "graphite");
        BridgeOracleTest.link(run, 6, 6, 46, 6);
        BridgeOracleTest.relative(run, 26, 30, 20, -24);
        BridgeOracleTest.compareRandom(run);
        run.compareAlso(MassDriverOracleTest::vanillaBullets, () -> shardBullets(run));
        // seen[0]: bolts in flight at once, at most; seen[1]: bolts fired after the load
        var seen = new int[2];
        var loaded = new boolean[1];
        var ids = new java.util.TreeSet<Integer>();
        // absolute entity ids, every frame: before the save and from the load on, which BlockRun starts from 0 on both sides
        run.compareAlso(() -> {
            seen[0] = Math.max(seen[0], mindustry.gen.Groups.bullet.size());
            for(mindustry.gen.Bullet b : mindustry.gen.Groups.bullet) if(loaded[0] && ids.add(b.id)) seen[1]++;
            return "next id " + BlockRun.lastId();
        }, () -> "next id " + run.shard.ids().peek());
        run.afterLoad(() -> {
            loaded[0] = true;
            // bullets aren't saved (BulletComp.java:26): the bolt in flight is gone on both sides, its payload too
            assertTrue(mindustry.gen.Groups.bullet.isEmpty(), "vanilla dropped the bolt in flight");
            assertEquals(0, run.shard.bullets().size(), "Shard dropped the bolt in flight");
            assertEquals(0, DriverBolts.of(run.shard).size(), "and its payload");
        });
        run.at(900, () -> BridgeOracleTest.relative(run, 6, 6, 20, 24));
        run.at(1300, () -> run.configureAny(26, 30, -1, -1));
        run.at(1450, () -> run.remove(6, 8));
        run.at(1650, () -> run.place("power-source", 6, 8));
        run.expectArrivals(200).run(FRAMES);
        // save with a bolt in flight
        int waited = run.runUntil(() -> !mindustry.gen.Groups.bullet.isEmpty() && DriverBolts.of(run.shard).size() > 0, 600);
        assertTrue(waited < 600, "a bolt is in flight to save with");
        int before = run.arrivals().size();
        run.continueFromSave(AFTER_LOAD);
        assertTrue(run.arrivals().size() > before, "the loaded drivers carry on");
        assertTrue(seen[1] > 0, "bolts fired after the load, ids compared");

        assertTrue(seen[0] > 0, "bolts flew");
        var arrivals = run.arrivals();
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 48 && a.item().equals("copper")), "B passed on A's copper");
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 48 && a.item().equals("graphite")), "B passed on C's graphite");
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 28 && a.item().equals("copper") && a.frame() > 900), "C passed on A's copper");
        syncBytesAreVanillas(run, 6, 6);
        syncBytesAreVanillas(run, 46, 6);
        syncBytesAreVanillas(run, 26, 30);
    }

    /** A block snapshot's body for the driver at (x, y): vanilla's {@code writeSync} against Shard's chunk without its revision. */
    static void syncBytesAreVanillas(BlockRun run, int x, int y){
        var bytes = new ByteArrayOutputStream();
        run.sim.build(x, y).writeSync(new Writes(new DataOutputStream(bytes)));
        var ids = ContentHeader.of(BlockRun.EPOCH).mapping(BlockRun.EPOCH);
        byte[] chunk = BuildingCodecs.builtIn().chunk(run.shard.buildings().at(x, y), BlockRun.EPOCH, ids);
        assertEquals(HexFormat.of().formatHex(bytes.toByteArray()), HexFormat.of().formatHex(Arrays.copyOfRange(chunk, 1, chunk.length)),
            "writeSync of the driver at (" + x + ", " + y + ")");
    }

    static String vanillaBullets(){
        var out = new StringBuilder("bullets");
        for(mindustry.gen.Bullet b : mindustry.gen.Groups.bullet){
            out.append(" [").append(b.id).append(' ').append(b.type.id).append(' ').append(b.x).append(',').append(b.y).append(" vel ")
                .append(b.vel.x).append(',').append(b.vel.y).append(" time ").append(b.time).append('/').append(b.lifetime)
                .append(" damage ").append(b.damage).append(" data ")
                .append(b.data instanceof mindustry.world.blocks.distribution.MassDriver.DriverBulletData data ? Arrays.toString(data.items) : "none")
                .append(']');
        }
        return out.toString();
    }

    static String shardBullets(BlockRun run){
        var out = new StringBuilder("bullets");
        Bullets table = run.shard.bullets();
        for(int row = 0; row < table.size(); row++){
            var data = DriverBolts.of(run.shard).get(Bullet.at(run.shard, row));
            out.append(" [").append(table.id[row]).append(' ').append(table.type[row]).append(' ').append(table.x[row]).append(',').append(table.y[row])
                .append(" vel ").append(table.velX[row]).append(',').append(table.velY[row]).append(" time ").append(table.time[row]).append('/')
                .append(table.lifetime[row]).append(" damage ").append(table.damage[row]).append(" data ")
                .append(data == null ? "none" : Arrays.toString(data.items)).append(']');
        }
        return out.toString();
    }

}
