package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.server.admin.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * The maps the server may host, as vanilla's {@code Maps.all()}: the default maps of a vanilla jar, then the custom
 * {@code .msav} files of a directory. Files are read when hosted, not kept.
 */
public final class MapCatalog{
    private static final System.Logger LOG = System.getLogger(MapCatalog.class.getName());

    /** Vanilla's {@code Maps.defaultMapNames}, in its order. */
    public static final List<String> DEFAULT_MAPS = List.of("maze", "fortress", "labyrinth", "islands", "tendrils", "caldera",
        "wasteland", "shattered", "fork", "triad", "mudFlats", "moltenLake", "archipelago", "debrisField", "domain", "veins",
        "glacier", "passage");

    private final List<Entry> maps;

    /**
     * A hostable map.
     *
     * @param file the file name without {@code .msav}, which also names the default world id
     * @param name the map's {@code name} tag, or the file name if it has none
     * @param author the map's {@code author} tag, or empty: vanilla's {@code hasTag("author")} is false for blank
     * @param location the jar entry or file, for messages
     */
    public record Entry(String file, String name, String author, boolean custom, String location, Source source){
        public Entry(String file, String name, boolean custom, String location, Source source){
            this(file, name, "", custom, location, source);
        }

        /** The file's bytes. */
        public byte[] read() throws IOException{
            return source.read();
        }
    }

    @FunctionalInterface
    public interface Source{
        byte[] read() throws IOException;
    }

    private MapCatalog(List<Entry> maps){
        this.maps = List.copyOf(maps);
    }

    public static MapCatalog of(List<Entry> maps){
        return new MapCatalog(maps);
    }

    /**
     * The default maps of {@code vanillaJar} (null for none) and the {@code .msav} files in {@code directory} (null or
     * missing for none), with their names read from each file's meta. A custom map that can't be read (an empty or
     * truncated file) is logged and left out, as vanilla's are; an unreadable default map is still an error.
     */
    public static MapCatalog load(Path vanillaJar, Path directory, MsavReader reader) throws IOException{
        var out = new ArrayList<Entry>();
        if(vanillaJar != null){
            if(!Files.isRegularFile(vanillaJar)) throw new FileNotFoundException("maps.vanilla-jar: no file " + vanillaJar);
            try(FileSystem jar = FileSystems.newFileSystem(vanillaJar)){
                for(String file : DEFAULT_MAPS){
                    Path entry = jar.getPath("maps", "default", file + ".msav");
                    if(!Files.exists(entry)) entry = jar.getPath("maps", file + ".msav");
                    if(!Files.exists(entry)) throw new FileNotFoundException(vanillaJar + " has no default map " + file);
                    byte[] bytes = Files.readAllBytes(entry);
                    String location = vanillaJar.getFileName() + "!" + entry;
                    String inside = entry.toString();
                    Map<String, String> meta = meta(reader, bytes, location);
                    out.add(new Entry(file, name(meta, file), author(meta), false, location, () -> readJar(vanillaJar, inside)));
                }
            }
        }
        if(directory != null && Files.isDirectory(directory)){
            List<Path> files;
            try(Stream<Path> list = Files.list(directory)){
                files = list.filter(path -> path.getFileName().toString().endsWith(".msav") && Files.isRegularFile(path)).sorted().toList();
            }
            for(Path path : files){
                String file = path.getFileName().toString();
                file = file.substring(0, file.length() - ".msav".length());
                Map<String, String> meta;
                try{
                    meta = meta(reader, Files.readAllBytes(path), path.toString());
                }catch(IOException e){
                    // vanilla's Maps.loadCustomMaps logs a custom map it can't read and skips it (Maps.java:149-150)
                    LOG.log(System.Logger.Level.ERROR, "Failed to load custom map file " + path + ": " + e.getMessage());
                    continue;
                }
                out.add(new Entry(file, name(meta, file), author(meta), true, path.toString(), () -> Files.readAllBytes(path)));
            }
        }
        return new MapCatalog(out);
    }

    private static Map<String, String> meta(MsavReader reader, byte[] bytes, String location) throws IOException{
        try{
            return reader.meta(bytes);
        }catch(RuntimeException e){
            throw new IOException(location + " is not a readable map: " + e.getMessage(), e);
        }
    }

    private static String name(Map<String, String> meta, String file){
        String name = meta.get("name");
        return name == null || name.isBlank() ? file : name;
    }

    private static String author(Map<String, String> meta){
        String author = meta.get("author");
        return author == null || author.isBlank() ? "" : author;
    }

    private static byte[] readJar(Path jar, String entry) throws IOException{
        try(FileSystem fs = FileSystems.newFileSystem(jar)){
            return Files.readAllBytes(fs.getPath(entry));
        }
    }

    public List<Entry> all(){
        return maps;
    }

    /**
     * The map {@code query} names, as vanilla's {@code host} finds one ({@code ServerControl.java:250}): its name tag with
     * color tags stripped and {@code _} read as a space, ignoring case. Shard also takes the file name.
     */
    public Optional<Entry> find(String query){
        String wanted = Names.stripColors(query).replace('_', ' ');
        for(Entry map : maps){
            if(Names.stripColors(map.name()).replace('_', ' ').equalsIgnoreCase(wanted)) return Optional.of(map);
        }
        for(Entry map : maps){
            if(map.file().equalsIgnoreCase(query)) return Optional.of(map);
        }
        return Optional.empty();
    }

    /** The world id a map gets when its entry names none: the file name in lower case, other characters as dashes. */
    public static String defaultId(Entry map){
        String id = map.file().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        id = id.replaceAll("^-+|-+$", "");
        return id.isEmpty() ? "world" : id;
    }
}
