package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.geom.*;

import java.util.*;

/**
 * Which processors link to each tile, so a change at a tile only wakes the processors that link there. Vanilla
 * re-checks every link of every processor each tick; Shard re-checks a processor's links in its next turn after one
 * of these events, which gives the same links because nothing else changes them.
 *
 * <p>The world reports, for every tile a building covers:
 * <ul>
 * <li>{@link #tileChanged} when its building is placed, removed or replaced (anything that changes
 *     {@code world.build(x, y)}), and when that building's team changes or it becomes invalid;</li>
 * <li>{@link #invalidateAll} when something else that {@code validLink} reads changes (a world load), to be safe.</li>
 * </ul>
 * A processor whose own team changes calls {@link Processor#markLinksDirty()}.
 */
public final class LinkIndex{
    private final Map<Integer, ArrayList<Processor>> byTile = new HashMap<>(); // lookups only, never iterated
    private final LinkedHashSet<Processor> all = new LinkedHashSet<>();

    LinkIndex(){
    }

    /** Something at tile {@code (x, y)} changed: every processor linking there re-checks its links. */
    public void tileChanged(int x, int y){
        ArrayList<Processor> list = byTile.get(Point2.pack(x, y));
        if(list == null) return;
        for(int i = 0; i < list.size(); i++) list.get(i).markLinksDirty();
    }

    /** Every processor re-checks its links. */
    public void invalidateAll(){
        for(Processor p : all) p.markLinksDirty();
    }

    /** The processors that link to tile {@code (x, y)}. */
    public int watchers(int x, int y){
        ArrayList<Processor> list = byTile.get(Point2.pack(x, y));
        return list == null ? 0 : list.size();
    }

    void update(Processor p, int[] before, int[] after){
        for(int pos : before){
            ArrayList<Processor> list = byTile.get(pos);
            if(list != null){
                list.remove(p);
                if(list.isEmpty()) byTile.remove(pos);
            }
        }
        for(int pos : after){
            ArrayList<Processor> list = byTile.computeIfAbsent(pos, key -> new ArrayList<>(1));
            if(!list.contains(p)) list.add(p);
        }
        if(after.length > 0) all.add(p);
        else all.remove(p);
    }
}
