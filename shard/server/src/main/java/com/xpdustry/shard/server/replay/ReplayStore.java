package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.server.replay.view.*;
import com.xpdustry.shard.sim.persist.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * The replay directory ({@code replay.directory}): bundles by build and match, and the published view streams.
 *
 * <pre>
 * &lt;root&gt;/bundles/&lt;build&gt;/&lt;match&gt;/   {@link Bundle}s
 * &lt;root&gt;/views/&lt;match&gt;.sview         a baked view stream, whole
 * &lt;root&gt;/views/&lt;match&gt;/               the same split: header.json, terrain, segments/&lt;i&gt; (live ones grow)
 * &lt;root&gt;/views/index.json            the published streams, newest first
 * </pre>
 *
 * A static host serves {@code views/} as it is, next to the viewer: Shard writes files and runs no HTTP server.
 */
public final class ReplayStore{
    private final Path root;

    public ReplayStore(Path root){
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root(){
        return root;
    }

    public Path views(){
        return root.resolve("views");
    }

    /** The bundles {@code build} recorded. */
    public Path bundles(String build){
        return root.resolve("bundles").resolve(build);
    }

    /** {@code match}'s bundle as this build records it. */
    public Bundle bundle(String match){
        return new Bundle(bundles(BuildInfo.id()).resolve(match));
    }

    /** Every bundle on disk, by build, then by match. */
    public SortedMap<String, List<Bundle>> all() throws IOException{
        var out = new TreeMap<String, List<Bundle>>();
        Path dir = root.resolve("bundles");
        if(!Files.isDirectory(dir)) return out;
        try(Stream<Path> builds = Files.list(dir)){
            for(Path build : builds.sorted().toList()){
                if(!Files.isDirectory(build)) continue;
                try(Stream<Path> matches = Files.list(build)){
                    out.put(build.getFileName().toString(), matches.filter(Files::isDirectory).sorted().map(Bundle::new).toList());
                }
            }
        }
        return out;
    }

    /** The baked view stream of {@code match}, if published. */
    public Path view(String match){
        return views().resolve(match + ".sview");
    }

    /**
     * Bakes {@code bundle}'s view stream ({@link Regenerator#bake}) and publishes it whole and split.
     *
     * @return the file's size
     */
    public long publish(Bundle bundle, Consumer<String> out) throws IOException{
        ViewFile file = new Regenerator(bundle).bake(out);
        byte[] bytes = file.encode();
        Bundle.write(view(bundle.match()), bytes);
        file.split(views().resolve(bundle.match()));
        Bundle.Manifest manifest = bundle.manifest();
        if(manifest.over()) bundle.manifest(manifest.withBaked(true));
        index(manifest, file.header(), bytes.length);
        return bytes.length;
    }

    /** Adds or replaces {@code manifest}'s match in {@code views/index.json}; {@code bytes} is the whole file's size, or null for a live stream. */
    synchronized void index(Bundle.Manifest manifest, ViewHeader header, Integer bytes) throws IOException{
        Path file = views().resolve("index.json");
        var entries = new ArrayList<Object>();
        if(Files.isRegularFile(file)){
            for(var node : Bundle.JSON.readTree(Files.readAllBytes(file))){
                if(!node.path("match").asString().equals(manifest.match())) entries.add(node);
            }
        }
        var entry = new LinkedHashMap<String, Object>();
        entry.put("title", manifest.mapName() + " · " + manifest.mode() + " · " + duration(header.ticks()) + (manifest.over() ? "" : " · live"));
        entry.put("note", header.producer() + " · " + manifest.match());
        entry.put("match", manifest.match());
        entry.put("ticks", header.ticks());
        if(bytes != null){
            entry.put("bytes", bytes);
            entry.put("file", manifest.match() + ".sview");
        }
        entry.put("stream", manifest.match() + "/");
        entries.addFirst(entry);
        Bundle.write(file, Bundle.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries));
    }

    static String duration(long ticks){
        long seconds = ticks / 60;
        return seconds >= 60 ? seconds / 60 + " min " + seconds % 60 + " s" : seconds + " s";
    }
}
