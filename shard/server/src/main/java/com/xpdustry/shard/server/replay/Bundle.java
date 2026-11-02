package com.xpdustry.shard.server.replay;

import tools.jackson.core.*;
import tools.jackson.databind.json.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * One match's replay bundle on disk (note 37 §5): the archival truth a view stream is
 * made from, and remade from.
 *
 * <pre>
 * &lt;replays&gt;/bundles/&lt;build&gt;/&lt;match&gt;/
 *   manifest.json             what was recorded: build, content, world, map, mode, ticks, state
 *   checkpoints/&lt;tick&gt;.ssnp   the world's match checkpoints (hard links to the checkpoint store's)
 *   log/&lt;tick&gt;.slog          the inputs admitted from &lt;tick&gt; to the next file ({@code InputLog})
 *   hashes                    "&lt;tick&gt; &lt;world hash&gt;" at every checkpoint tick, from the live world
 *   view/terrain              the view stream's terrain chunk at its start (its baseline)
 *   view/&lt;tick&gt;.seg          the segment whose keyframe is at &lt;tick&gt;, deflated, as recorded live
 *   view/segments             "&lt;tick&gt; &lt;frames&gt; &lt;sha-256&gt; [partial]" of each recorded segment
 * </pre>
 *
 * Every file is written to a temporary name and moved into place. A segment the live recorder couldn't write whole
 * (the world was restored in its middle) is missing or partial; {@link Regenerator} makes it from the checkpoint at
 * or before it and the log, on the build that recorded the bundle.
 */
public final class Bundle{
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern NUMBERED = Pattern.compile("(\\d{12})\\.(ssnp|slog|seg)");

    private final Path dir;

    public Bundle(Path dir){
        this.dir = dir;
    }

    public Path dir(){
        return dir;
    }

    public String match(){
        return dir.getFileName().toString();
    }

    public boolean exists(){
        return Files.isRegularFile(dir.resolve("manifest.json"));
    }

    /**
     * What a bundle says about itself.
     *
     * @param build the Shard build that recorded it: only that build replays it
     * @param content the content epoch's hash
     * @param systems {@code Checkpoint.systemsId} of the world
     * @param start the first tick recorded: a multiple of {@code keyframeEvery}, where the view stream starts
     * @param end the last tick recorded so far
     * @param state {@code recording}, {@code suspended} (the world hibernated in this match), {@code ended}
     * @param winner the winning team if the match ended in a game over, else -1
     * @param baked whether the view stream is whole and published ({@code views/<match>.sview})
     */
    public record Manifest(int format, String build, String content, long systems, String game, String world, String match, String map,
                           String mapName, String mode, boolean pvp, String started, String ended, long start, long end, int checkpointEvery,
                           int frameEvery, int keyframeEvery, String state, int winner, boolean baked){
        public static final int FORMAT = 1;

        public Manifest withEnd(long end, String state){
            return new Manifest(format, build, content, systems, game, world, match, map, mapName, mode, pvp, started, ended, start, end, checkpointEvery,
                frameEvery, keyframeEvery, state, winner, baked);
        }

        public Manifest ended(String when, long end, int winner){
            return new Manifest(format, build, content, systems, game, world, match, map, mapName, mode, pvp, started, when, start, end, checkpointEvery,
                frameEvery, keyframeEvery, "ended", winner, baked);
        }

        public Manifest withBaked(boolean baked){
            return new Manifest(format, build, content, systems, game, world, match, map, mapName, mode, pvp, started, ended, start, end, checkpointEvery,
                frameEvery, keyframeEvery, state, winner, baked);
        }

        public boolean over(){
            return state.equals("ended");
        }
    }

    public Manifest manifest() throws IOException{
        try{
            return JSON.readValue(Files.readAllBytes(dir.resolve("manifest.json")), Manifest.class);
        }catch(JacksonException e){
            throw new IOException(dir.resolve("manifest.json") + " doesn't read: " + e.getMessage(), e);
        }
    }

    public void manifest(Manifest manifest) throws IOException{
        write(dir.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
    }

    // ---- pieces

    public Path checkpoint(long tick){
        return dir.resolve("checkpoints").resolve(name(tick, "ssnp"));
    }

    public Path log(long from){
        return dir.resolve("log").resolve(name(from, "slog"));
    }

    public Path segment(long tick){
        return dir.resolve("view").resolve(name(tick, "seg"));
    }

    public Path terrain(){
        return dir.resolve("view").resolve("terrain");
    }

    public SortedSet<Long> checkpoints() throws IOException{
        return numbered(dir.resolve("checkpoints"), "ssnp");
    }

    public SortedSet<Long> logs() throws IOException{
        return numbered(dir.resolve("log"), "slog");
    }

    public SortedSet<Long> segmentFiles() throws IOException{
        return numbered(dir.resolve("view"), "seg");
    }

    /** A recorded segment's line in {@code view/segments}. */
    public record SegmentEntry(long tick, int frames, String hash, boolean partial){
    }

    public SortedMap<Long, SegmentEntry> segments() throws IOException{
        var out = new TreeMap<Long, SegmentEntry>();
        for(String line : lines(dir.resolve("view").resolve("segments"))){
            String[] parts = line.split(" ");
            out.put(Long.parseLong(parts[0]), new SegmentEntry(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), parts[2],
                parts.length > 3 && parts[3].equals("partial")));
        }
        return out;
    }

    public void segments(SortedMap<Long, SegmentEntry> segments) throws IOException{
        var text = new StringBuilder();
        for(SegmentEntry entry : segments.values()){
            text.append(entry.tick()).append(' ').append(entry.frames()).append(' ').append(entry.hash()).append(entry.partial() ? " partial" : "").append('\n');
        }
        write(dir.resolve("view").resolve("segments"), text.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** The live world's hash at each checkpoint tick: its value, then its sections ({@code name=hex}). */
    public SortedMap<Long, String> hashes() throws IOException{
        var out = new TreeMap<Long, String>();
        for(String line : lines(dir.resolve("hashes"))){
            int space = line.indexOf(' ');
            out.put(Long.parseLong(line.substring(0, space)), line.substring(space + 1));
        }
        return out;
    }

    public void hashes(SortedMap<Long, String> hashes) throws IOException{
        var text = new StringBuilder();
        hashes.forEach((tick, hash) -> text.append(tick).append(' ').append(hash).append('\n'));
        write(dir.resolve("hashes"), text.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** The bytes stored on disk, in total. */
    public long bytes() throws IOException{
        if(!Files.isDirectory(dir)) return 0;
        try(Stream<Path> files = Files.walk(dir)){
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try{
                    return Files.size(file);
                }catch(IOException e){
                    return 0;
                }
            }).sum();
        }
    }

    /** Deletes the bundle. */
    public void delete() throws IOException{
        if(!Files.exists(dir)) return;
        try(Stream<Path> files = Files.walk(dir)){
            for(Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    static String name(long tick, String extension){
        return String.format("%012d.%s", tick, extension);
    }

    private static SortedSet<Long> numbered(Path dir, String extension) throws IOException{
        var out = new TreeSet<Long>();
        if(!Files.isDirectory(dir)) return out;
        try(Stream<Path> files = Files.list(dir)){
            for(Path file : files.toList()){
                Matcher matcher = NUMBERED.matcher(file.getFileName().toString());
                if(matcher.matches() && matcher.group(2).equals(extension)) out.add(Long.parseLong(matcher.group(1)));
            }
        }
        return out;
    }

    private static List<String> lines(Path file) throws IOException{
        if(!Files.isRegularFile(file)) return List.of();
        return Files.readAllLines(file, StandardCharsets.US_ASCII).stream().filter(line -> !line.isBlank()).toList();
    }

    /** Writes {@code bytes} to a temporary file beside {@code file}, then moves it into place. */
    static void write(Path file, byte[] bytes) throws IOException{
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** {@code target} at {@code link}: a hard link when the file system allows, else a copy. */
    static void link(Path target, Path link) throws IOException{
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        try{
            Files.createLink(link, target);
        }catch(IOException | UnsupportedOperationException e){
            Path temporary = link.resolveSibling(link.getFileName() + ".tmp");
            Files.copy(target, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    @Override
    public String toString(){
        return "Bundle[" + dir + "]";
    }
}
