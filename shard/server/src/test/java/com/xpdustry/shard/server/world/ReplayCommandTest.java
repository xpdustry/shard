package com.xpdustry.shard.server.world;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@code shard replay}: arguments, exit codes and output. */
public class ReplayCommandTest{
    @TempDir
    Path dir;

    record Run(int status, String out, String err){
    }

    static Run run(String... args){
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int status = ReplayCommand.run(List.of(args), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** A world that builds crates, takes members, arms its fuse for tick 90 and crashes there. */
    static CrashBundle crash(){
        var world = new World(TestWorlds.config(9));
        for(Submission build : TestWorlds.crates(8)) world.inbox().offer(build);
        for(int i = 0; i < 20; i++) world.tick();
        byte[] checkpoint = Checkpoint.capture(world).encode(Compression.FAST);
        var marks = new ArrayList<CrashBundle.Mark>();
        world.inbox().offer(TestWorlds.join(4));
        world.inbox().offer(TestWorlds.admin(new TestWorlds.Arm(90)));
        try{
            while(true){
                if(world.ticks() == 50) world.inbox().offer(TestWorlds.leave(4));
                world.tick();
                if(world.ticks() % 10 == 0) marks.add(new CrashBundle.Mark(world.ticks(), world.hash()));
            }
        }catch(IllegalStateException e){
            return CrashBundle.of(checkpoint, world, marks, world.ticks(), e, new TreeMap<>());
        }
    }

    Path write(CrashBundle bundle) throws IOException{
        Path file = dir.resolve("crash.zip");
        Files.write(file, bundle.encode());
        return file;
    }

    @Test
    void aBundleReplaysToItsFailure() throws IOException{
        Run run = run(write(crash()).toString(), "--hash-every", "30");
        assertEquals(0, run.status(), run.out() + run.err());
        assertTrue(run.out().contains("restored tick 20"), run.out());
        assertTrue(run.out().contains("tick 60 "), "hashes every 30 ticks: " + run.out());
        assertTrue(run.out().contains("threw java.lang.IllegalStateException: fuse blew at tick 90 (as recorded)"), run.out());
        assertTrue(run.out().endsWith("replay reproduced the failure at tick 90\n"), run.out());
    }

    @Test
    void untilStopsEarly() throws IOException{
        Run run = run(write(crash()).toString(), "--until", "55");
        assertEquals(0, run.status(), run.err());
        assertTrue(run.out().endsWith("replay matched through tick 55\n"), run.out());
    }

    @Test
    void aDivergenceExitsWithOne() throws IOException{
        CrashBundle bundle = crash();
        var marks = new ArrayList<>(bundle.hashes());
        CrashBundle.Mark first = marks.getFirst();
        var sections = new ArrayList<WorldHash.Section>();
        for(WorldHash.Section section : first.hash().sections()){
            sections.add(section.name().equals("buildings") ? new WorldHash.Section("buildings", ~section.hash()) : section);
        }
        marks.set(0, new CrashBundle.Mark(first.tick(), WorldHash.of(sections)));
        Run run = run(write(new CrashBundle(bundle.checkpoint(), bundle.logStart(), bundle.entries(), marks, bundle.failedTick(), bundle.error(),
            bundle.labels())).toString());
        assertEquals(1, run.status(), run.out());
        assertTrue(run.out().contains("replay diverged: the hash at tick " + first.tick() + " differs, first in buildings"), run.out());
    }

    @Test
    void badArgumentsPrintTheUsage() throws IOException{
        for(String[] args : new String[][]{{}, {"--until"}, {"a.zip", "b.zip"}, {"--frobnicate", "a.zip"}, {"a.zip", "--hash-every", "x"}}){
            Run run = run(args);
            assertEquals(2, run.status(), Arrays.toString(args));
            assertTrue(run.err().contains("Usage: shard replay"), run.err());
        }
        assertEquals(0, run("--help").status());
        Path junk = dir.resolve("junk.zip");
        Files.write(junk, new byte[]{1, 2, 3});
        Run run = run(junk.toString());
        assertEquals(2, run.status());
        assertTrue(run.err().contains("can't read"), run.err());
        assertEquals(2, run(dir.resolve("missing.zip").toString()).status());
    }

    /**
     * Vanilla's content with the tank assembler of a kind no class registers ({@code replay-test-kind}): every vanilla
     * block is ported (W5.1), so a world this build can't rebuild needs content of its own, found by {@link Probe}.
     */
    static final ContentEpoch PROBE = new Swapped(Epoch.vanilla(),
        new TreeMap<>(Map.of(Epoch.vanilla().block("tank-assembler").id(), new Rekinded(Epoch.vanilla().block("tank-assembler"), KindKey.of("replay-test-kind")))));

    @Test
    void aWorldThisBuildCantRebuildIsBadInput() throws IOException{
        // a hosted world on probe content with a kind of its own: no systems of this build match it
        KindRegistry kinds = HostedSystems.KINDS.withKind(KindKey.of("replay-test-kind"), PassthroughBuilding.class, PassthroughBuilding::new);
        WorldConfig hosted = HostedSystems.template(Epoch.vanilla(), 3, 256);
        var world = new World(WorldConfig.of(PROBE, 1, 1, 3).withSystems(hosted.systems()).withKinds(kinds).withInboxCapacity(256));
        for(int i = 0; i < 5; i++) world.tick();
        byte[] checkpoint = Checkpoint.capture(world).encode(Compression.FAST);
        world.tick();
        Path file = write(CrashBundle.of(checkpoint, world, List.of(), world.ticks(), new IllegalStateException("boom"), new TreeMap<>()));

        assertEquals(2, run(file.toString()).status(), "other systems, without --force");
        // --force falls back to the plain systems, whose kinds don't cover vanilla's blocks
        Run run = run(file.toString(), "--force");
        assertEquals(2, run.status(), run.out() + run.err());
        assertTrue(run.err().startsWith("shard replay: can't rebuild the world of " + file + " on this build: unsupported kinds in content "), run.err());
    }

    /** Finds {@link #PROBE} for {@code shard replay}. */
    @com.xpdustry.shard.common.registry.Register
    public static final class Probe implements ContentSource{
        @Override
        public ContentEpoch find(String hash){
            return PROBE.hash().equals(hash) ? PROBE : null;
        }
    }

    /** A block of another kind; it builds and saves as that kind. */
    record Rekinded(BlockDef block, KindKey kind) implements BlockDef{
        @Override
        public int id(){
            return block.id();
        }

        @Override
        public String name(){
            return block.name();
        }

        @Override
        public KindKey buildKind(){
            return kind;
        }

        @Override
        public boolean baked(){
            return true;
        }

        @Override
        public com.xpdustry.shard.content.schema.Struct fields(){
            return block.fields();
        }

        @Override
        public ItemDef itemDrop(){
            return block.itemDrop();
        }

        @Override
        public int size(){
            return block.size();
        }

        @Override
        public float health(){
            return block.health();
        }

        @Override
        public boolean update(){
            return block.update();
        }

        @Override
        public boolean noUpdateDisabled(){
            return block.noUpdateDisabled();
        }

        @Override
        public boolean hasItems(){
            return block.hasItems();
        }

        @Override
        public boolean hasLiquids(){
            return block.hasLiquids();
        }

        @Override
        public boolean hasPower(){
            return block.hasPower();
        }
    }

    /** {@code base} with some blocks swapped, under a hash of its own. */
    record Swapped(ContentEpoch base, SortedMap<Integer, BlockDef> swapped) implements ContentEpoch{
        @Override
        public String hash(){
            return base.hash() + "+" + swapped.values().stream().map(b -> b.name() + ":" + b.kind()).toList();
        }

        @Override
        public int blocks(){
            return base.blocks();
        }

        @Override
        public BlockDef block(int id){
            BlockDef block = swapped.get(id);
            return block != null ? block : base.block(id);
        }

        @Override
        public int count(ContentType type){
            return base.count(type);
        }

        @Override
        public ContentDef get(ContentType type, int id){
            return type == ContentType.BLOCK ? block(id) : base.get(type, id);
        }

        @Override
        public ContentDef find(ContentType type, String name){
            ContentDef def = base.find(type, name);
            return def instanceof BlockDef block ? block(block.id()) : def;
        }

        @Override
        public int items(){
            return base.items();
        }

        @Override
        public int liquids(){
            return base.liquids();
        }

        @Override
        public int units(){
            return base.units();
        }

        @Override
        public UnitDef unit(int id){
            return base.unit(id);
        }
    }

}
