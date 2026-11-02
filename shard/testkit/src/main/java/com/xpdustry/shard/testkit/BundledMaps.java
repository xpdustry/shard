package com.xpdustry.shard.testkit;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** The maps bundled in vanilla's jar ({@code maps/**}{@code /*.msav}), which is on every test class path through the oracle. */
public final class BundledMaps{
    /** The 18 maps of vanilla's {@code Maps.defaultMapNames}, in its order. */
    public static final List<String> DEFAULT = List.of("maze", "fortress", "labyrinth", "islands", "tendrils", "caldera",
        "wasteland", "shattered", "fork", "triad", "mudFlats", "moltenLake", "archipelago", "debrisField", "domain", "veins",
        "glacier", "passage");

    private static volatile List<String> all;

    private BundledMaps(){
    }

    /**
     * The file of a bundled map, found as {@code Vanilla.findMap} finds internal ones: a file name ({@code fortress},
     * a default map first), an internal path ({@code serpulo/groundZero}) or a resource ({@code maps/default/fortress.msav}).
     */
    public static byte[] read(String name){
        return resource(path(name));
    }

    /** The resource name of {@link #read}'s map ({@code maps/default/fortress.msav}). */
    public static String path(String name){
        if(name.startsWith("maps/") && name.endsWith(".msav")) return name;
        String internal = "maps/" + (name.endsWith(".msav") ? name : name + ".msav");
        if(all().contains(internal)) return internal;
        String file = "/" + name + ".msav";
        for(String path : all()) if(path.startsWith("maps/default/") && path.endsWith(file)) return path;
        for(String path : all()) if(path.endsWith(file)) return path;
        throw new IllegalArgumentException("no bundled map named '" + name + "'");
    }

    /** Every {@code .msav} under {@code maps/} in the vanilla jar, as class path resource names, sorted. */
    public static List<String> all(){
        List<String> maps = all;
        if(maps == null) all = maps = list();
        return maps;
    }

    private static byte[] resource(String path){
        try(InputStream in = BundledMaps.class.getClassLoader().getResourceAsStream(path)){
            if(in == null) throw new IllegalArgumentException(path + " is not on the class path (is the vanilla jar?)");
            return in.readAllBytes();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> list(){
        URL url = Objects.requireNonNull(BundledMaps.class.getClassLoader().getResource("maps/default/fortress.msav"), "the vanilla jar is not on the class path");
        String jar = url.toString().substring("jar:".length(), url.toString().indexOf("!/"));
        try(FileSystem fs = FileSystems.newFileSystem(Path.of(URI.create(jar)))){
            try(var paths = Files.walk(fs.getPath("/maps"))){
                return paths.map(Path::toString).filter(p -> p.endsWith(".msav")).map(p -> p.substring(1)).sorted().toList();
            }
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }
}
