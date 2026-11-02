package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Shard's native checkpoint (ARCHITECTURE.md §4 "Checkpoint contract"): every authoritative piece of a world at one
 * tick boundary, in a versioned, checksummed file that records the content hash and the build and schema ids.
 *
 * <pre>{@code
 * Capture capture = Checkpoint.capture(world);              // on the world's thread, between ticks
 * byte[] file = capture.encode(Compression.FAST);         // on any thread
 * World restored = Checkpoint.decode(file).restore(config); // continues bit-identically
 * }</pre>
 *
 * <p>A checkpoint is read by the build that wrote it, or by one with the same format version ({@code Container.FORMAT}):
 * the version is the first thing checked, and any other fails with a {@link CheckpointException} naming both versions.
 * There is no migration between builds. Restoring needs an epoch with the stored content hash and the same parts, in
 * the same order; replay compares {@link Header#systems()} and refuses a mismatch.
 */
public final class Checkpoint{
    private final Container.Decoded file;

    private Checkpoint(Container.Decoded file){
        this.file = file;
    }

    /** Copies {@code world}'s state; call it between ticks, on the world's thread. */
    public static Capture capture(World world){
        return CheckpointWriter.capture(world);
    }

    /** Checks and inflates a checkpoint file; fails with a {@link CheckpointException} if it is damaged. */
    public static Checkpoint decode(byte[] bytes){
        Container.Decoded decoded = Container.decode(bytes);
        if(!decoded.header().kind().equals("checkpoint")) throw new CheckpointException("this file is a " + decoded.header().kind() + ", not a checkpoint");
        return new Checkpoint(decoded);
    }

    /** Reads only a file's header, after checking its footer checksum. */
    public static Header header(byte[] bytes){
        return Container.header(bytes);
    }

    public Header header(){
        return file.header();
    }

    /** The encoded file's size in bytes. */
    public int fileLength(){
        return file.fileLength();
    }

    /** The raw size of each section, by name. */
    public SortedMap<String, Integer> sectionSizes(){
        var out = new TreeMap<String, Integer>();
        file.sections().forEach((name, bytes) -> out.put(name, bytes.length));
        return out;
    }

    /**
     * The config the world was made with, on {@code content}: its size, seed, inbox and journal, with the built-in
     * systems and kinds.
     */
    public WorldConfig config(ContentEpoch content){
        var reading = new Reading(null, content, CheckpointWriter.readSchemas(file.section("schema")));
        var source = new Source("world", file.section("world"));
        var in = new CheckpointIn(source, reading);
        in.begin(reading.section("world"));
        int width = in.i("width"), height = in.i("height");
        long seed = in.l("seed");
        int inbox = in.i("inboxCapacity");
        source = new Source("journal", file.section("journal"));
        in = new CheckpointIn(source, reading);
        in.begin(reading.section("journal"));
        int kind = in.b("kind");
        int retention = in.i("retention");
        Journal journal = switch(kind){
            case 0 -> Journal.discarding();
            case 1 -> new MemoryJournal(retention);
            default -> throw new CheckpointException("the world used a custom journal; pass its config to restore()");
        };
        return new WorldConfig(content, width, height, seed, Systems.builtIn(), KindRegistry.builtIn(), inbox, journal);
    }

    /** {@link #config(ContentEpoch)} on the epoch a registered {@link ContentSource} has for the stored hash. */
    public WorldConfig config(){
        return config(ContentSource.resolve(file.header().content()));
    }

    /** The world at the checkpoint's tick, ready to tick. {@code config}'s content must have the stored hash. */
    public World restore(WorldConfig config){
        return CheckpointReader.restore(file, config);
    }

    /** The command log's position at the checkpoint: a crash bundle stores the entries from here on. */
    public long logPosition(){
        var reading = new Reading(null, null, CheckpointWriter.readSchemas(file.section("schema")));
        var source = new Source("log", file.section("log"));
        var in = new CheckpointIn(source, reading);
        in.begin(reading.section("log"));
        in.implicit("entries");
        long position = in.l("position");
        in.end();
        source.finish();
        return position;
    }

    /**
     * A hash of what a world runs: each phase's handler classes in order, the part classes in order, and each kind
     * key's class ({@code tile} for a tile kind). Two worlds with the same one tick the same way, build and content
     * permitting.
     */
    public static long systemsId(WorldConfig config){
        var hasher = new Hasher();
        Systems systems = config.systems();
        for(Phase phase : Phase.values()){
            List<PhaseHandler> handlers = systems.handlers(phase);
            hasher.str(phase.name()).i(handlers.size());
            for(PhaseHandler handler : handlers) hasher.str(handler.getClass().getName());
        }
        List<Class<? extends WorldPart>> parts = systems.parts();
        hasher.i(parts.size());
        for(Class<? extends WorldPart> part : parts) hasher.str(part.getName());
        List<KindKey> keys = config.kinds().keys();
        hasher.i(keys.size());
        for(KindKey key : keys) hasher.str(key.name()).str(config.kinds().tileKind(key) ? "tile" : config.kinds().type(key).getName());
        return hasher.value();
    }
}
