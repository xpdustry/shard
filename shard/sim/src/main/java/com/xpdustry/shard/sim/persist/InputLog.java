package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A run of {@link CommandLog} entries as a file: the {@code log/<tick>} pieces of a replay bundle
 * (note 37 §5). A {@link Container} of kind {@code inputs}, whose header names the build,
 * schema, content and systems, and whose tick is the first tick the run covers.
 *
 * <p>Each entry is its tick, its length and its fields, so a run can be cut at a tick without decoding it
 * ({@link #truncate}): a world restored to an earlier tick starts a timeline the later entries aren't part of.
 *
 * <p>Inputs are made outside the world they act on, and a bundle replays them into a world restored from a checkpoint,
 * so they can't hold world objects by checkpoint index. They hold them by identity instead (a building by its tile, any
 * other entity by its id; W1.4): the {@link Writer} stores that, and {@link Reader#take} decodes the entries of one
 * tick against the world they're replayed into, right before that tick, where the objects they named are.
 */
public final class InputLog{
    /**
     * Collects entries as a world admits them, encoding each at once: identities are read from live objects, so call
     * {@link #add} on the world's thread. {@link #encode} may run anywhere once nothing more is added.
     */
    public static final class Writer{
        private final Capturing capturing = Capturing.identities();
        private final Sink sink = new Sink(4096);
        private final CheckpointOut out = new CheckpointOut(sink, capturing);
        private final long from;
        private long last = Long.MIN_VALUE;
        private int count;
        /** Where each entry starts in the sink, and its tick, for {@link #truncate}. */
        private int[] starts = new int[64];
        private long[] ticks = new long[64];

        /** @param from the first tick the run covers */
        public Writer(long from){
            this.from = from;
        }

        public void add(CommandLog.Entry entry){
            if(entry.tick() < from || entry.tick() < last) throw new IllegalArgumentException("entry at tick " + entry.tick() + " after tick " + Math.max(from, last));
            last = entry.tick();
            if(count == starts.length){
                starts = Arrays.copyOf(starts, count * 2);
                ticks = Arrays.copyOf(ticks, count * 2);
            }
            starts[count] = sink.size();
            ticks[count] = entry.tick();
            sink.l(entry.tick());
            int length = sink.size();
            sink.i(0);
            out.object(entry);
            sink.i(length, sink.size() - length - 4);
            count++;
        }

        /** Forgets the entries from {@code tick} on: the world went back to that tick. */
        public void truncate(long tick){
            int keep = 0;
            while(keep < count && ticks[keep] < tick) keep++;
            if(keep == count) return;
            sink.truncate(starts[keep]);
            count = keep;
            last = keep == 0 ? Long.MIN_VALUE : ticks[keep - 1];
        }

        /** The tick of the last entry, or {@link Long#MIN_VALUE}. */
        public long last(){
            return last;
        }

        public int size(){
            return count;
        }

        public long from(){
            return from;
        }

        /** Bytes encoded so far, before compression. */
        public int rawBytes(){
            return sink.size();
        }

        /**
         * The file.
         *
         * @param content the content epoch's hash the inputs' content references resolve in
         * @param systems {@link Checkpoint#systemsId} of the world
         */
        public byte[] encode(String content, long systems, SortedMap<String, String> labels, Compression compression){
            var entries = new Sink(sink.size() + 4);
            entries.i(count);
            entries.bytes(sink.buffer(), 0, sink.size());
            List<Schema> schemas = capturing.schemas();
            var header = new Header("inputs", BuildInfo.id(), CheckpointWriter.schemaId(schemas), content, systems, from, labels);
            return Container.encode(header, List.of(new Container.Section("entries", entries.buffer(), entries.size()),
                CheckpointWriter.schemaSection(schemas)), compression);
        }
    }

    /** Reads only a file's header, after checking its footer checksum. */
    public static Header header(byte[] file){
        Header header = Container.header(file);
        if(!header.kind().equals("inputs")) throw new CheckpointException("this file is a " + header.kind() + ", not an input log");
        return header;
    }

    /** Checks and inflates a file; its content references resolve in {@code content}, which must have the stored hash. */
    public static Reader read(byte[] file, ContentEpoch content){
        Container.Decoded decoded = Container.decode(file);
        Header header = decoded.header();
        if(!header.kind().equals("inputs")) throw new CheckpointException("this file is a " + header.kind() + ", not an input log");
        if(!content.hash().equals(header.content())){
            throw new CheckpointException("the log was written with content " + header.content() + ", not " + content.hash());
        }
        return new Reader(header, decoded.section("entries"), new Reading(null, content, CheckpointWriter.readSchemas(decoded.section("schema"))));
    }

    /** Entries in order, decoded one tick at a time. */
    public static final class Reader{
        private final Header header;
        private final Source source;
        private final Reading reading;
        private final int count;
        private int read;
        private long next;

        private Reader(Header header, byte[] entries, Reading reading){
            this.header = header;
            this.source = new Source("entries", entries);
            this.reading = reading;
            reading.identities = true;
            count = source.length(source.i(), 13);
            next = count == 0 ? Long.MAX_VALUE : source.l();
            if(next < header.tick()) throw new CheckpointException("the log starts at tick " + header.tick() + " but holds an entry at tick " + next);
        }

        public Header header(){
            return header;
        }

        /** The first tick the run covers. */
        public long from(){
            return header.tick();
        }

        /** Entries in the file. */
        public int size(){
            return count;
        }

        /** The tick of the next entry not taken yet, or {@link Long#MAX_VALUE} after the last. */
        public long nextTick(){
            return next;
        }

        /**
         * The entries admitted at {@code tick}, in admission order, their world objects found in {@code world} as it is
         * now: right before that tick, when it is the world they're replayed into. Entries of earlier ticks are an
         * error: a replay takes every tick in order.
         */
        public List<CommandLog.Entry> take(long tick, World world){
            if(next < tick) throw new CheckpointException("the log has an entry for tick " + next + ", before " + tick);
            if(next != tick) return List.of();
            var out = new ArrayList<CommandLog.Entry>();
            Reading.Bound bound = reading.bind(world);
            try{
                var in = new CheckpointIn(source, reading);
                while(next == tick){
                    int length = source.length(source.i(), 1);
                    int end = source.position() + length;
                    CommandLog.Entry entry = in.object(CommandLog.Entry.class);
                    if(source.position() != end) throw new CheckpointException("entry " + read + " is " + length + " bytes long, but its fields took " + (source.position() - end + length));
                    if(entry == null || entry.tick() != tick) throw new CheckpointException("entry " + read + " is not at tick " + tick);
                    out.add(entry);
                    read++;
                    long following = read < count ? source.l() : Long.MAX_VALUE;
                    if(following < next) throw new CheckpointException("entry " + read + " goes back to tick " + following);
                    next = following;
                }
            }finally{
                bound.close();
            }
            if(read == count) source.finish();
            return out;
        }

        /** Passes over the entries before {@code tick} without decoding them: a replay that starts later in the run. */
        public void skip(long tick){
            while(next < tick){
                source.skip(source.length(source.i(), 1));
                read++;
                long following = read < count ? source.l() : Long.MAX_VALUE;
                if(following < next) throw new CheckpointException("entry " + read + " goes back to tick " + following);
                next = following;
            }
            if(read == count) source.finish();
        }

        /** Every entry left, with no world: fails on an entry that holds a world object. */
        public List<CommandLog.Entry> all(){
            var out = new ArrayList<CommandLog.Entry>();
            while(next != Long.MAX_VALUE) out.addAll(take(next, null));
            return out;
        }
    }

    /**
     * {@code file} without its entries from {@code tick} on, or {@code file} itself if it has none; null if it would
     * be empty because the run starts at or after {@code tick}. The entries are copied, not decoded.
     */
    public static byte[] truncate(byte[] file, long tick){
        Container.Decoded decoded = Container.decode(file);
        Header header = decoded.header();
        if(!header.kind().equals("inputs")) throw new CheckpointException("this file is a " + header.kind() + ", not an input log");
        if(header.tick() >= tick) return null;
        byte[] entries = decoded.section("entries");
        var source = new Source("entries", entries);
        int count = source.length(source.i(), 13), keep = 0;
        while(keep < count){
            int start = source.position();
            if(source.l() >= tick){
                var out = new Sink(start + 4);
                out.i(keep);
                out.bytes(entries, 4, start - 4);
                return Container.encode(header, List.of(new Container.Section("entries", out.buffer(), out.size()),
                    new Container.Section("schema", decoded.section("schema"), decoded.section("schema").length)), Compression.FAST);
            }
            source.skip(source.length(source.i(), 1));
            keep++;
        }
        return file;
    }

    private InputLog(){}
}
