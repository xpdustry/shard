package com.xpdustry.shard.server.world;

import com.xpdustry.shard.sim.persist.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Checkpoint generations on disk, one directory per world:
 *
 * <pre>
 * &lt;root&gt;/&lt;world&gt;/gen-000000000042.ssnp   generations, numbered up
 * &lt;root&gt;/&lt;world&gt;/CURRENT                 the name of the current generation
 * &lt;root&gt;/&lt;world&gt;/crash-&lt;tick&gt;-&lt;n&gt;.zip       crash bundles
 * &lt;root&gt;/&lt;world&gt;/match/000000001200.ssnp   match checkpoints, by tick
 * </pre>
 *
 * {@link #publish} makes a generation current only once it is durable and verified: write a temporary file, fsync,
 * rename, fsync the directory, read it back and decode it in full, then write {@code CURRENT} the same way. If any
 * step fails, {@code CURRENT} still names the last good generation. {@link #load} trusts {@code CURRENT} but checks
 * it, and falls back to the newest generation that decodes.
 *
 * <p>World time never goes back: {@link #publish} refuses a capture from an earlier tick than the generation
 * {@code CURRENT} names ({@link StaleCaptureException}), so a save that finishes encoding late can't replace a newer
 * one. Not thread-safe per world: callers publish one at a time, which makes that check atomic.
 *
 * <p>Match checkpoints ({@link #publishMatch}) are kept apart, by tick, for the whole match: retention never deletes
 * them, and {@link #archive} moves them away with the world. A replay bundle takes its {@code checkpoints/<tick>} from
 * them (note 37 §5).
 */
public final class CheckpointStore{
    private static final Pattern GENERATION = Pattern.compile("gen-(\\d{12})\\.ssnp");
    private static final Pattern MATCH = Pattern.compile("(\\d{12})\\.ssnp");
    private static final System.Logger LOG = System.getLogger(CheckpointStore.class.getName());

    private final Path root;
    private final FileOps files;
    private final int keep;

    /** A durable generation. */
    public record Generation(long number, Path file, Header header, int length){
    }

    /**
     * A generation read back for restoring.
     *
     * @param recovery why {@code CURRENT} wasn't used, or null if it was
     */
    public record Loaded(Generation generation, byte[] bytes, String recovery){
    }

    /** A capture older than the current generation, refused before anything was written. */
    public static final class StaleCaptureException extends IOException{
        private final long tick, currentTick;

        StaleCaptureException(WorldId world, long tick, String current, long currentTick){
            super(world + ": the capture at tick " + tick + " is older than " + current + " at tick " + currentTick
                + ", which CURRENT names; it was not published");
            this.tick = tick;
            this.currentTick = currentTick;
        }

        /** The refused capture's tick. */
        public long tick(){
            return tick;
        }

        /** The tick of the generation that stays current. */
        public long currentTick(){
            return currentTick;
        }
    }

    public CheckpointStore(Path root, FileOps files, int keep){
        if(keep < 1) throw new IllegalArgumentException("keep " + keep);
        this.root = root;
        this.files = files;
        this.keep = keep;
    }

    public Path dir(WorldId world){
        return root.resolve(world.name());
    }

    /**
     * Moves {@code world}'s generations aside, to {@code <id>.replaced-<millis>}, so a fresh world under the same id
     * starts over: the store would refuse its captures as older than the old ones.
     *
     * @return the directory they went to, or null if the world had none
     */
    public Path archive(WorldId world, long millis) throws IOException{
        Path dir = dir(world);
        if(files.list(dir).isEmpty()) return null;
        Path archived = root.resolve(world.name() + ".replaced-" + millis);
        files.move(dir, archived);
        files.forceDirectory(root);
        return archived;
    }

    /**
     * Publishes {@code checkpoint} as {@code world}'s next current generation; on failure the current one stays.
     *
     * @throws StaleCaptureException if it is from an earlier tick than the current generation
     */
    public Generation publish(WorldId world, byte[] checkpoint) throws IOException{
        Path dir = dir(world);
        files.createDirectories(dir);
        long number = newest(dir) + 1;
        String name = name(number);
        Path file = dir.resolve(name), temporary = dir.resolve(name + ".tmp");
        try{
            refuseStale(world, dir, Checkpoint.header(checkpoint).tick());
            files.write(temporary, checkpoint);
            files.move(temporary, file);
            files.forceDirectory(dir);
            byte[] back = files.read(file);
            if(!Arrays.equals(back, checkpoint)) throw new IOException(file + " reads back different bytes than were written");
            Header header = verify(back, file);
            Path current = dir.resolve("CURRENT"), currentTemporary = dir.resolve("CURRENT.tmp");
            files.write(currentTemporary, (name + "\n").getBytes(StandardCharsets.US_ASCII));
            files.move(currentTemporary, current);
            files.forceDirectory(dir);
            retain(dir, number);
            return new Generation(number, file, header, back.length);
        }catch(IOException | RuntimeException e){
            quietly(() -> files.delete(temporary));
            quietly(() -> files.delete(dir.resolve("CURRENT.tmp")));
            if(!name.equals(currentName(dir))) quietly(() -> files.delete(file));
            throw e instanceof IOException io ? io : new IOException("publishing " + name + " for " + world + " failed", e);
        }
    }

    /** The current generation, checked; else the newest that decodes; empty if none does. */
    public Optional<Loaded> load(WorldId world) throws IOException{
        Path dir = dir(world);
        String current = currentName(dir);
        String recovery = null;
        if(current != null){
            Path file = dir.resolve(current);
            try{
                byte[] bytes = files.read(file);
                return Optional.of(new Loaded(new Generation(number(current), file, verify(bytes, file), bytes.length), bytes, null));
            }catch(IOException | CheckpointException e){
                recovery = "CURRENT names " + current + ", which is unreadable (" + e.getMessage() + ")";
            }
        }else if(!generations(dir).isEmpty()){
            recovery = "there is no CURRENT";
        }
        List<String> names = generations(dir);
        for(int i = names.size() - 1; i >= 0; i--){
            String name = names.get(i);
            if(name.equals(current)) continue;
            Path file = dir.resolve(name);
            try{
                byte[] bytes = files.read(file);
                var generation = new Generation(number(name), file, verify(bytes, file), bytes.length);
                String note = recovery + "; fell back to " + name;
                LOG.log(System.Logger.Level.WARNING, world + ": " + note);
                return Optional.of(new Loaded(generation, bytes, note));
            }catch(IOException | CheckpointException e){
                recovery = (recovery == null ? "" : recovery + "; ") + name + " is unreadable (" + e.getMessage() + ")";
            }
        }
        if(recovery != null) throw new IOException(world + " has no readable checkpoint: " + recovery);
        return Optional.empty();
    }

    /** The generation file names in {@code world}'s directory, oldest first. */
    public List<String> generations(WorldId world) throws IOException{
        return generations(dir(world));
    }

    /** Where {@code world}'s match checkpoints are. */
    public Path matchDir(WorldId world){
        return dir(world).resolve("match");
    }

    /**
     * Writes {@code checkpoint} as {@code world}'s match checkpoint at its tick, durable and verified as a generation is,
     * replacing one at the same tick.
     *
     * @return its file
     */
    public Path publishMatch(WorldId world, byte[] checkpoint) throws IOException{
        Path dir = matchDir(world);
        files.createDirectories(dir);
        String name;
        try{
            name = matchName(Checkpoint.header(checkpoint).tick());
        }catch(CheckpointException e){
            throw new IOException("a match checkpoint for " + world + " doesn't decode", e);
        }
        Path file = dir.resolve(name), temporary = dir.resolve(name + ".tmp");
        boolean moved = false;
        try{
            files.write(temporary, checkpoint);
            files.move(temporary, file);
            moved = true;
            files.forceDirectory(dir);
            byte[] back = files.read(file);
            if(!Arrays.equals(back, checkpoint)) throw new IOException(file + " reads back different bytes than were written");
            verify(back, file);
            return file;
        }catch(IOException | RuntimeException e){
            quietly(() -> files.delete(temporary));
            if(moved) quietly(() -> files.delete(file));
            throw e instanceof IOException io ? io : new IOException("writing " + name + " for " + world + " failed", e);
        }
    }

    /** The ticks of {@code world}'s match checkpoints, in order. */
    public List<Long> matchTicks(WorldId world) throws IOException{
        var ticks = new ArrayList<Long>();
        for(String name : files.list(matchDir(world))){
            Matcher matcher = MATCH.matcher(name);
            if(matcher.matches()) ticks.add(Long.parseLong(matcher.group(1)));
        }
        return ticks;
    }

    /** {@code world}'s match checkpoint at {@code tick}, checked, or empty if there is none. */
    public Optional<byte[]> loadMatch(WorldId world, long tick) throws IOException{
        if(!matchTicks(world).contains(tick)) return Optional.empty();
        Path file = matchDir(world).resolve(matchName(tick));
        byte[] bytes = files.read(file);
        verify(bytes, file);
        return Optional.of(bytes);
    }

    /**
     * Deletes {@code world}'s match checkpoints from after {@code tick}: restored to {@code tick}, the world starts a
     * timeline they aren't part of.
     *
     * @return how many went
     */
    public int dropMatchAfter(WorldId world, long tick) throws IOException{
        int dropped = 0;
        for(long at : matchTicks(world)){
            if(at <= tick) continue;
            files.delete(matchDir(world).resolve(matchName(at)));
            dropped++;
        }
        if(dropped > 0) files.forceDirectory(matchDir(world));
        return dropped;
    }

    /** Writes a crash bundle next to the generations and returns its path. */
    public Path writeBundle(WorldId world, long tick, byte[] bundle) throws IOException{
        Path dir = dir(world);
        files.createDirectories(dir);
        List<String> names = files.list(dir);
        int n = 0;
        while(names.contains("crash-" + tick + "-" + n + ".zip")) n++;
        Path file = dir.resolve("crash-" + tick + "-" + n + ".zip"), temporary = dir.resolve("crash-" + tick + "-" + n + ".zip.tmp");
        files.write(temporary, bundle);
        files.move(temporary, file);
        files.forceDirectory(dir);
        return file;
    }

    private static Header verify(byte[] bytes, Path file){
        try{
            return Checkpoint.decode(bytes).header();
        }catch(CheckpointException e){
            throw new CheckpointException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Refuses a capture at {@code tick} if the generation {@code CURRENT} names is from a later tick. A missing or
     * unreadable current generation refuses nothing: {@link #load} would fall back past it anyway.
     */
    private void refuseStale(WorldId world, Path dir, long tick) throws StaleCaptureException{
        String current = currentName(dir);
        if(current == null) return;
        long currentTick;
        try{
            currentTick = Checkpoint.header(files.read(dir.resolve(current))).tick();
        }catch(IOException | CheckpointException e){
            return;
        }
        if(tick < currentTick) throw new StaleCaptureException(world, tick, current, currentTick);
    }

    private void retain(Path dir, long current){
        List<String> names = generations(dir);
        for(int i = 0; i < names.size() - keep; i++){
            String name = names.get(i);
            if(number(name) == current) continue;
            quietly(() -> files.delete(dir.resolve(name)));
        }
    }

    private List<String> generations(Path dir){
        try{
            return files.list(dir).stream().filter(name -> GENERATION.matcher(name).matches()).toList();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    private long newest(Path dir){
        List<String> names = generations(dir);
        return names.isEmpty() ? 0 : number(names.getLast());
    }

    private String currentName(Path dir){
        try{
            String text = new String(files.read(dir.resolve("CURRENT")), StandardCharsets.US_ASCII).strip();
            return GENERATION.matcher(text).matches() ? text : null;
        }catch(IOException e){
            return null;
        }
    }

    private static String name(long number){
        return String.format("gen-%012d.ssnp", number);
    }

    private static String matchName(long tick){
        return String.format("%012d.ssnp", tick);
    }

    private static long number(String name){
        Matcher matcher = GENERATION.matcher(name);
        if(!matcher.matches()) throw new IllegalArgumentException(name);
        return Long.parseLong(matcher.group(1));
    }

    private interface Step{
        void run() throws IOException;
    }

    private static void quietly(Step step){
        try{
            step.run();
        }catch(IOException | RuntimeException e){
            LOG.log(System.Logger.Level.DEBUG, "cleanup failed", e);
        }
    }
}
