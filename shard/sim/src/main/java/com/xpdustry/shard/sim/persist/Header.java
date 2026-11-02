package com.xpdustry.shard.sim.persist;

import java.util.*;

/**
 * What a checkpoint file says about itself, readable without restoring it (note 34 "A pointer-free, versioned
 * format").
 *
 * @param kind what the file holds: {@code checkpoint}, or {@code log} for a crash bundle's command log
 * @param build the build that wrote it ({@link BuildInfo#id()}); {@code shard replay} needs the same build
 * @param schema a hash of every stored class and section schema: two files with the same one store the same fields
 * @param content the content epoch's hash; restoring needs an epoch with this hash
 * @param systems a hash of the phase handlers, parts and kinds the world ran with; replay needs the same systems
 * @param tick the world tick at capture
 * @param labels the runtime's own facts (world id, generation, wall time), sorted by key
 */
public record Header(String kind, String build, long schema, String content, long systems, long tick,
                     SortedMap<String, String> labels){
    public Header{
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(content, "content");
        labels = Collections.unmodifiableSortedMap(new TreeMap<>(labels));
    }

    public Header withLabels(SortedMap<String, String> labels){
        return new Header(kind, build, schema, content, systems, tick, labels);
    }

    /** The label {@code key}, or null. */
    public String label(String key){
        return labels.get(key);
    }

    void write(Sink sink){
        sink.str(kind);
        sink.str(build);
        sink.l(schema);
        sink.str(content);
        sink.l(systems);
        sink.l(tick);
        sink.i(labels.size());
        labels.forEach((key, value) -> {
            sink.str(key);
            sink.str(value);
        });
    }

    static Header read(Source source){
        String kind = source.str(), build = source.str();
        long schema = source.l();
        String content = source.str();
        long systems = source.l(), tick = source.l();
        int count = source.length(source.i(), 8);
        var labels = new TreeMap<String, String>();
        for(int i = 0; i < count; i++) labels.put(source.str(), source.str());
        if(kind == null || build == null || content == null) throw source.corrupt("a header field is null");
        source.finish();
        return new Header(kind, build, schema, content, systems, tick, labels);
    }
}
