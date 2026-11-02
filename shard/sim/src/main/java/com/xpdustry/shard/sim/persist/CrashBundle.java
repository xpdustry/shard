package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

/**
 * What a world leaves behind when a tick throws (ARCHITECTURE.md §4 "Crash bundle"): its last durable checkpoint,
 * every command log entry since that checkpoint, the hashes the world reported on the way, and the error. A zip:
 *
 * <ul>
 *   <li>{@code checkpoint.ssnp}: the checkpoint file, as published;</li>
 *   <li>{@code log.ssnp}: a {@link Container} of kind {@code log} with the entries, their schemas and the hashes;</li>
 *   <li>{@code manifest.txt}, {@code hashes.txt}, {@code error.txt}: the same facts for people.</li>
 * </ul>
 *
 * {@link Replay} reads it on the same build and re-runs the ticks. Inputs that hold world objects are stored by
 * identity ({@link InputLog}); a crash bundle decodes its whole log up front, with no world, so it refuses those and
 * says so. A replay bundle replays them.
 *
 * @param checkpoint the checkpoint file the replay starts from
 * @param logStart the command log position of the first entry, which is the checkpoint's
 * @param entries the log from the checkpoint up to and including the failed tick's admissions
 * @param hashes the world hashes reported since the checkpoint, by tick
 * @param failedTick the tick that threw, or -1 if the bundle was taken without a failure
 * @param error the error's stack trace, or empty
 * @param labels the runtime's facts (world id, wall time)
 */
public record CrashBundle(byte[] checkpoint, long logStart, List<CommandLog.Entry> entries, List<Mark> hashes,
                          long failedTick, String error, SortedMap<String, String> labels){
    /** A world hash after the world reached {@code tick}. */
    public record Mark(long tick, WorldHash hash){
    }

    public CrashBundle{
        Objects.requireNonNull(checkpoint, "checkpoint");
        entries = List.copyOf(entries);
        hashes = List.copyOf(hashes);
        Objects.requireNonNull(error, "error");
        labels = Collections.unmodifiableSortedMap(new TreeMap<>(labels));
    }

    /**
     * A bundle for a world that threw while running tick {@code failedTick}: the entries from {@code checkpoint}'s
     * log position on are copied from the world's log. Cheap enough for the tick thread; {@link #encode} may run
     * anywhere.
     */
    public static CrashBundle of(byte[] checkpoint, World world, List<Mark> hashes, long failedTick, Throwable error,
                                 SortedMap<String, String> labels){
        long start = Checkpoint.decode(checkpoint).logPosition();
        CommandLog log = world.log();
        if(log.start() > start){
            throw new IllegalStateException("the log was trimmed to " + log.start() + ", past the checkpoint's position " + start);
        }
        return new CrashBundle(checkpoint, start, List.copyOf(log.since(start)), hashes, failedTick, trace(error), labels);
    }

    /** The first line of the error: its class and message. */
    public String errorSummary(){
        int end = error.indexOf('\n');
        return (end < 0 ? error : error.substring(0, end)).strip();
    }

    public byte[] encode(){
        Header header = Checkpoint.header(checkpoint);
        var bytes = new ByteArrayOutputStream(checkpoint.length + 4096);
        try(var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)){
            put(zip, "manifest.txt", manifest(header).getBytes(StandardCharsets.UTF_8));
            put(zip, "checkpoint.ssnp", checkpoint);
            put(zip, "log.ssnp", log(header));
            put(zip, "hashes.txt", hashText().getBytes(StandardCharsets.UTF_8));
            put(zip, "error.txt", error.getBytes(StandardCharsets.UTF_8));
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** {@link #decode(byte[], ContentEpoch)} on the epoch a registered {@link ContentSource} has for the checkpoint. */
    public static CrashBundle decode(byte[] zip){
        return decode(zip, null);
    }

    /** Reads a bundle; its log's content references resolve in {@code content} (null: resolve by the stored hash). */
    public static CrashBundle decode(byte[] zip, ContentEpoch content){
        var files = new TreeMap<String, byte[]>();
        try(var in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)){
            for(ZipEntry entry; (entry = in.getNextEntry()) != null; ){
                if(files.put(entry.getName(), in.readAllBytes()) != null) throw new CheckpointException("the bundle has two " + entry.getName());
            }
        }catch(IOException e){
            throw new CheckpointException("not a crash bundle: " + e.getMessage(), e);
        }
        byte[] checkpoint = file(files, "checkpoint.ssnp");
        Container.Decoded log = Container.decode(file(files, "log.ssnp"));
        if(!log.header().kind().equals("log")) throw new CheckpointException("log.ssnp is a " + log.header().kind());
        String contentHash = log.header().content();
        if(content == null) content = ContentSource.resolve(contentHash);
        if(!content.hash().equals(contentHash)) throw new CheckpointException("the bundle was written with content " + contentHash + ", not " + content.hash());
        var reading = new Reading(null, content, CheckpointWriter.readSchemas(log.section("schema")));
        reading.identities = true;

        var source = new Source("entries", log.section("entries"));
        var in = new CheckpointIn(source, reading);
        long start = source.l();
        long failed = source.l();
        int count = source.length(source.i(), 4);
        var entries = new ArrayList<CommandLog.Entry>(count);
        for(int i = 0; i < count; i++) entries.add(in.object(CommandLog.Entry.class));
        source.finish();

        source = new Source("hashes", log.section("hashes"));
        int marks = source.length(source.i(), 16);
        var hashes = new ArrayList<Mark>(marks);
        for(int i = 0; i < marks; i++){
            long tick = source.l();
            long value = source.l();
            int sections = source.length(source.i(), 12);
            var list = new ArrayList<WorldHash.Section>(sections);
            for(int s = 0; s < sections; s++) list.add(new WorldHash.Section(source.str(), source.l()));
            var hash = new WorldHash(value, list);
            if(WorldHash.of(list).value() != value) throw new CheckpointException("the hash at tick " + tick + " doesn't match its sections");
            hashes.add(new Mark(tick, hash));
        }
        source.finish();
        String error = new String(file(files, "error.txt"), StandardCharsets.UTF_8);
        return new CrashBundle(checkpoint, start, entries, hashes, failed, error, log.header().labels());
    }

    private byte[] log(Header checkpoint){
        var capturing = Capturing.identities();
        var entriesSink = new Sink(entries.size() * 64 + 64);
        var out = new CheckpointOut(entriesSink, capturing);
        entriesSink.l(logStart);
        entriesSink.l(failedTick);
        entriesSink.i(entries.size());
        for(CommandLog.Entry entry : entries) out.object(entry);

        var hashSink = new Sink(hashes.size() * 256 + 16);
        hashSink.i(hashes.size());
        for(Mark mark : hashes){
            hashSink.l(mark.tick());
            hashSink.l(mark.hash().value());
            hashSink.i(mark.hash().sections().size());
            for(WorldHash.Section section : mark.hash().sections()){
                hashSink.str(section.name());
                hashSink.l(section.hash());
            }
        }
        List<Schema> schemas = capturing.schemas();
        var header = new Header("log", BuildInfo.id(), CheckpointWriter.schemaId(schemas), checkpoint.content(), checkpoint.systems(),
            failedTick, labels);
        return Container.encode(header, List.of(
            new Container.Section("entries", entriesSink.buffer(), entriesSink.size()),
            new Container.Section("hashes", hashSink.buffer(), hashSink.size()),
            CheckpointWriter.schemaSection(schemas)), Compression.FAST);
    }

    private String manifest(Header checkpoint){
        var out = new StringBuilder();
        out.append("shard crash bundle\n");
        out.append("build=").append(BuildInfo.id()).append('\n');
        out.append("content=").append(checkpoint.content()).append('\n');
        out.append("systems=").append(Long.toHexString(checkpoint.systems())).append('\n');
        out.append("checkpointTick=").append(checkpoint.tick()).append('\n');
        out.append("failedTick=").append(failedTick).append('\n');
        out.append("logStart=").append(logStart).append('\n');
        out.append("entries=").append(entries.size()).append('\n');
        out.append("hashes=").append(hashes.size()).append('\n');
        out.append("error=").append(errorSummary()).append('\n');
        labels.forEach((key, value) -> out.append("label.").append(key).append('=').append(value).append('\n'));
        return out.toString();
    }

    private String hashText(){
        var out = new StringBuilder();
        for(Mark mark : hashes) out.append(mark.tick()).append(' ').append(mark.hash()).append('\n');
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException{
        var entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] file(SortedMap<String, byte[]> files, String name){
        byte[] bytes = files.get(name);
        if(bytes == null) throw new CheckpointException("the crash bundle has no " + name);
        return bytes;
    }

    static String trace(Throwable error){
        if(error == null) return "";
        var text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
