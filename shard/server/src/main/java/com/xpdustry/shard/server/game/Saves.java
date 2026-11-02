package com.xpdustry.shard.server.game;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.world.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * The console's save slots ({@code save}, {@code load}, {@code saves}; ServerControl.java:1183-1232): vanilla msav files
 * named {@code <slot>.msav} in one directory ({@code persistence.saves-directory}, vanilla's {@code config/saves}).
 * <ul>
 *   <li>{@link #save}: a world as vanilla's {@code SaveIO.save} writes it ({@link HostedWorld#save}), written to a
 *   temporary file and moved over the slot, so a crash leaves the old save or the new one;</li>
 *   <li>{@link #load}: hosts the slot's file as a new world ({@link Host#hostSave}), as vanilla's {@code SaveIO.load}
 *   then {@code state.set(playing)};</li>
 *   <li>{@link #list}: the slots, by name.</li>
 * </ul>
 * A slot name is letters, digits, {@code _} and {@code -}, at most 64 of them ({@link #SLOT}), and its file must resolve
 * inside the directory: vanilla's {@code saveDirectory.child(arg[0] + ".msav")} takes any text, so {@code ../x} writes
 * outside it (unsafe input, refused here).
 */
public final class Saves{
    /** What a slot name may be. */
    public static final Pattern SLOT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** Vanilla's {@code saveExtension}. */
    public static final String EXTENSION = "msav";

    private final Host host;
    private final Path directory;

    public Saves(Host host, Path directory){
        this.host = host;
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory(){
        return directory;
    }

    /**
     * The file of slot {@code name}.
     *
     * @throws IllegalArgumentException if the name isn't a slot name, or its file would fall outside the directory
     */
    public Path slot(String name){
        if(name == null || !SLOT.matcher(name).matches()){
            throw new IllegalArgumentException("a save slot is 1 to 64 letters, digits, '_' or '-', not '" + name + "'");
        }
        Path file = directory.resolve(name + "." + EXTENSION).normalize();
        if(!directory.equals(file.getParent())) throw new IllegalArgumentException("save slot '" + name + "' is outside " + directory);
        return file;
    }

    /** Saves {@code world} to slot {@code name}: the file once it is written. */
    public CompletableFuture<Path> save(HostedWorld world, String name){
        Path file;
        try{
            file = slot(name);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        return world.save(System.currentTimeMillis()).thenApplyAsync(bytes -> {
            try{
                Files.createDirectories(directory);
                Path temp = Files.createTempFile(directory, name + ".", ".tmp");
                try{
                    Files.write(temp, bytes);
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }finally{
                    Files.deleteIfExists(temp);
                }
                return file;
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Hosts slot {@code name} as a new world {@code id} (the slot's name when null).
     *
     * @throws IllegalArgumentException (in the future) if the slot or the id is bad, or the slot has no file
     */
    public CompletableFuture<HostedWorld> load(String name, String id){
        Path file;
        WorldId world;
        try{
            file = slot(name);
            if(!Files.isRegularFile(file)) throw new IllegalArgumentException("no save in slot '" + name + "'");
            String worldId = id == null ? name.toLowerCase(Locale.ROOT).replace('_', '-') : id;
            if(!com.xpdustry.shard.server.config.ConfigKeys.WORLD_ID.matcher(worldId).matches()){
                throw new IllegalArgumentException("bad world id '" + worldId + "': lower case letters, digits and dashes");
            }
            world = WorldId.of(worldId);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        var entry = new MapCatalog.Entry(name, name, true, file.toString(), () -> Files.readAllBytes(file));
        return host.hostSave(world, entry);
    }

    /** The slots in the directory, by name; none if it doesn't exist. */
    public List<String> list() throws IOException{
        if(!Files.isDirectory(directory)) return List.of();
        var out = new ArrayList<String>();
        try(var files = Files.list(directory)){
            for(Path file : (Iterable<Path>)files::iterator){
                String name = file.getFileName().toString();
                if(Files.isRegularFile(file) && name.endsWith("." + EXTENSION)) out.add(name.substring(0, name.length() - EXTENSION.length() - 1));
            }
        }
        Collections.sort(out);
        return out;
    }
}
