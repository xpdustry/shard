package com.xpdustry.shard.sim.persist;

import java.util.*;

/**
 * A world's state copied at one tick boundary into raw section bytes (ARCHITECTURE.md §4: "Capture is a bulk copy
 * at a tick boundary; compression runs off-thread"). Taking one is the only step on the world's thread; it shares
 * nothing with the world, so {@link #encode} may run on any thread, any time later.
 */
public final class Capture{
    private final Header header;
    private final List<Container.Section> sections;
    private final long logPosition;

    Capture(Header header, List<Container.Section> sections, long logPosition){
        this.header = header;
        this.sections = List.copyOf(sections);
        this.logPosition = logPosition;
    }

    public Header header(){
        return header;
    }

    /** The world tick it was taken at. */
    public long tick(){
        return header.tick();
    }

    /**
     * The command log's position at the capture: the entries from here on are what a crash bundle needs on top of
     * this checkpoint.
     */
    public long logPosition(){
        return logPosition;
    }

    /** The raw size of all sections, before compression. */
    public long rawBytes(){
        long total = 0;
        for(Container.Section section : sections) total += section.length();
        return total;
    }

    /** The raw size of each section, by name, for reports. */
    public SortedMap<String, Integer> sectionSizes(){
        var out = new TreeMap<String, Integer>();
        for(Container.Section section : sections) out.put(section.name(), section.length());
        return out;
    }

    /** A section's raw bytes, for the coverage check and the crash bundle. */
    byte[] section(String name){
        for(Container.Section section : sections){
            if(section.name().equals(name)) return Arrays.copyOf(section.bytes(), section.length());
        }
        throw new IllegalArgumentException("no section " + name);
    }

    /** The checkpoint file, with {@code labels} added to the header (world id, generation, wall time). */
    public byte[] encode(Compression compression, SortedMap<String, String> labels){
        var merged = new TreeMap<>(header.labels());
        merged.putAll(labels);
        return Container.encode(header.withLabels(merged), sections, compression);
    }

    public byte[] encode(Compression compression){
        return Container.encode(header, sections, compression);
    }
}
