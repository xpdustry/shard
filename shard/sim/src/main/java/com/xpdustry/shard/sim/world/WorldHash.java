package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * A world's state hash, one section per subsystem in a fixed order (ARCHITECTURE.md §4). Two worlds with equal
 * hashes are, as far as the hash can tell, in the same state; {@link #firstDifference(WorldHash)} names the first
 * subsystem that diverged. Tests check it every tick.
 */
public record WorldHash(long value, List<Section> sections){
    public WorldHash{
        sections = List.copyOf(sections);
    }

    public static WorldHash of(List<Section> sections){
        var total = new Hasher();
        for(Section section : sections) total.str(section.name()).l(section.hash());
        return new WorldHash(total.value(), sections);
    }

    /** The section called {@code name}. */
    public long section(String name){
        for(Section section : sections){
            if(section.name().equals(name)) return section.hash();
        }
        throw new NoSuchElementException("no hash section " + name + " in " + this);
    }

    /** The name of the first section that differs from {@code other}'s, or empty if every section matches. */
    public Optional<String> firstDifference(WorldHash other){
        for(int i = 0; i < Math.max(sections.size(), other.sections.size()); i++){
            Section mine = i < sections.size() ? sections.get(i) : null;
            Section theirs = i < other.sections.size() ? other.sections.get(i) : null;
            if(!Objects.equals(mine, theirs)) return Optional.of(mine != null ? mine.name() : theirs.name());
        }
        return Optional.empty();
    }

    @Override
    public String toString(){
        var out = new ArrayList<String>(sections.size());
        for(Section section : sections) out.add(section.name() + "=" + String.format("%016x", section.hash()));
        return String.format("%016x[", value) + String.join(", ", out) + "]";
    }

    public record Section(String name, long hash){
    }
}
