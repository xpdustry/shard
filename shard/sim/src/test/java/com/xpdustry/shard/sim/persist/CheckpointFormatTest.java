package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.nio.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Damaged and mismatched checkpoints fail with a {@link CheckpointException} that says what is wrong. */
class CheckpointFormatTest{
    static byte[] file(){
        return CheckpointTest.file(CheckpointTest.run(Fixtures.churning(30), 80));
    }

    static String failure(Runnable action){
        return assertThrows(CheckpointException.class, action::run).getMessage();
    }

    @Test
    void aTruncatedFileIsRejectedBeforeAnythingIsRead(){
        byte[] file = file();
        for(int length : new int[]{0, 7, 30, file.length / 2, file.length - 12, file.length - 1}){
            byte[] cut = Arrays.copyOf(file, length);
            String message = failure(() -> Checkpoint.decode(cut));
            assertTrue(message.contains("truncated"), length + ": " + message);
        }
    }

    @Test
    void aFlippedByteFailsTheChecksum(){
        byte[] file = file();
        for(int at : new int[]{3, 40, file.length / 3, file.length - 20}){
            byte[] bad = file.clone();
            bad[at] ^= 0x10;
            String message = failure(() -> Checkpoint.decode(bad));
            assertTrue(message.contains("checksum") || message.contains("magic"), at + ": " + message);
        }
    }

    /** The file with its format version replaced and its footer checksum made good again. */
    private static byte[] withFormat(byte[] file, int format){
        byte[] changed = file.clone();
        ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putShort(8, (short)format).putInt(changed.length - 12, Container.crc(changed, 0, changed.length - 12));
        return changed;
    }

    @Test
    void anotherFormatVersionIsNamed(){
        String expected = "this checkpoint has format version %d; this build reads version %d only (checkpoints aren't migrated between builds)";
        assertEquals(4, Container.FORMAT, "bump this test with the format");
        for(int other : new int[]{1, 2, 3, 5, 0xFFFF}){
            byte[] changed = withFormat(file(), other);
            assertEquals(expected.formatted(other, Container.FORMAT), failure(() -> Checkpoint.decode(changed)), "decode, version " + other);
            assertEquals(expected.formatted(other, Container.FORMAT), failure(() -> Checkpoint.header(changed)), "header, version " + other);
        }
    }

    @Test
    void anOlderCheckpointIsRefusedAtTheBoundary(){
        // a version 1 file, as before W5.3 added the world's paused flag: refused by its version, before any field is read
        byte[] old = withFormat(file(), 1);
        var thrown = assertThrows(CheckpointException.class, () -> Checkpoint.decode(old).restore(Fixtures.config(30)));
        assertTrue(thrown.getMessage().contains("version 1") && thrown.getMessage().contains("version " + Container.FORMAT), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("field"), "refused at the boundary, not at a field: " + thrown.getMessage());
    }

    @Test
    void bothPauseValuesRoundTrip(){
        for(boolean paused : new boolean[]{false, true}){
            World live = CheckpointTest.run(Fixtures.churning(31), 60);
            live.paused(paused);
            // a paused world doesn't tick everything; the flag is read at each tick's start, so tick once to take it
            live.tick();
            assertEquals(paused, live.paused());
            World restored = Checkpoint.decode(CheckpointTest.file(live)).restore(Fixtures.config(31));
            assertEquals(paused, restored.paused(), "paused " + paused);
            assertEquals(live.hash(), restored.hash(), "paused " + paused);
        }
    }

    @Test
    void anotherContentEpochIsRefused(){
        Checkpoint checkpoint = Checkpoint.decode(file());
        var other = new ToyContent(List.of(ToyContent.INSTANCE.block(0), ToyContent.INSTANCE.block(1), ToyContent.INSTANCE.block(2)).stream()
            .map(ToyContent.Block.class::cast).toList());
        var config = new WorldConfig(other, 48, 48, 30, Fixtures.config(30).systems(), Fixtures.config(30).kinds(), 4096, new MemoryJournal());
        String message = failure(() -> checkpoint.restore(config));
        assertTrue(message.contains("toy-4") && message.contains("toy-3"), message);
    }

    @Test
    void anotherSizeIsRefused(){
        Checkpoint checkpoint = Checkpoint.decode(file());
        var config = new WorldConfig(ToyContent.INSTANCE, 64, 48, 30, Fixtures.config(30).systems(), Fixtures.config(30).kinds(), 4096, new MemoryJournal());
        assertEquals("the checkpoint is 48×48; the config says 64×48", failure(() -> checkpoint.restore(config)));
    }

    @Test
    void missingPartsAreRefused(){
        Checkpoint checkpoint = Checkpoint.decode(file());
        String message = failure(() -> checkpoint.restore(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 30)));
        assertTrue(message.contains("doesn't run"), message);
    }

    /** A part whose stored field name the test can change, as a rename between builds would. */
    public static final class Gauge implements WorldPart, Checkpointable{
        static String name = "level";
        public int level = 7;

        @Override
        public void write(CheckpointOut out){
            out.i(name, level);
        }

        @Override
        public void read(CheckpointIn in){
            level = in.i(name);
        }

        @Override
        public WorldPart copy(CopyContext context){
            var out = new Gauge();
            out.level = level;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            hasher.i(level);
        }
    }

    @Test
    void aRenamedFieldIsNamedOnRestore(){
        WorldConfig config = Fixtures.config(31).withSystems(Fixtures.config(31).systems().with(Gauge.class, Gauge::new));
        byte[] file = Checkpoint.capture(new World(config)).encode(Compression.FAST);
        assertEquals(7, Checkpoint.decode(file).restore(config).part(Gauge.class).level);
        try{
            Gauge.name = "amount";
            String message = failure(() -> Checkpoint.decode(file).restore(config));
            assertEquals(Gauge.class.getName() + ": the code reads amount:int as field 0 but the checkpoint has level:int", message);
        }finally{
            Gauge.name = "level";
        }
    }

    /** A controller with state and no way to store it. */
    public static final class Wander implements UnitController{
        public int steps;

        @Override
        public void updateUnit(Unit unit){
            steps++;
        }

        @Override
        public UnitController copy(CopyContext context){
            return new Wander();
        }

        @Override
        public void hash(Hasher hasher){
            hasher.i(steps);
        }
    }

    @Test
    void stateWithoutACodecFailsTheCaptureAndNamesTheFields(){
        World world = Worlds.quiet(32);
        Unit unit = Unit.create(world, ToyContent.DRONE, 1);
        unit.controller = new Wander();
        unit.add();
        String message = failure(() -> Checkpoint.capture(world));
        assertTrue(message.contains(Wander.class.getName()) && message.contains("Wander.steps"), message);
    }

    @Test
    void lambdasCannotHideInRecords(){
        World world = Worlds.quiet(33);
        world.timers().run(5f, new Holder(() -> {}));
        String message = failure(() -> Checkpoint.capture(world));
        assertTrue(message.contains("lambda"), message);
    }

    public record Holder(Runnable action) implements Timers.Task{
        @Override
        public void run(World world){
            action.run();
        }
    }
}
