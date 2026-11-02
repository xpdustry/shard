package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.Flat.*;

import java.util.*;

/**
 * A run's {@link Flat}s at its checkpoints, stored as leaf changes from one checkpoint to the next, so a 10k-tick
 * trace costs memory in proportion to what changed rather than to the world. {@link Differential} records the
 * reference side here when the two sides can't run in lockstep (vanilla against vanilla), then replays it.
 */
final class Trace{
    private sealed interface Change{
    }

    /** A new entity, or one whose label or kind changed. */
    private record Put(Entity entity) implements Change{
    }

    private record Drop(Key key) implements Change{
    }

    /** A leaf's new value, which may be null; a removed leaf is a {@link LeafDrop}. */
    private record Leaf(Key key, String path, Object value) implements Change{
    }

    private record LeafDrop(Key key, String path) implements Change{
    }

    private record Frame(long tick, List<Change> changes){
    }

    private final String engine;
    private final ArrayList<Frame> frames = new ArrayList<>();
    private EnumSet<Scope> absent;
    private Flat last;

    Trace(String engine){
        this.engine = engine;
    }

    void record(Flat next){
        if(absent == null){
            absent = EnumSet.noneOf(Scope.class);
            absent.addAll(next.absent());
        }
        var changes = new ArrayList<Change>();
        TreeMap<Key, Entity> before = last == null ? new TreeMap<>() : last.map();
        for(Entity entity : next.map().values()){
            Entity old = before.get(entity.key());
            if(old == null || !Objects.equals(old.label(), entity.label()) || !Objects.equals(old.kind(), entity.kind())){
                changes.add(new Put(entity));
                continue;
            }
            for(var leaf : entity.leaves().entrySet()){
                Map<String, Object> was = old.leaves();
                if(!was.containsKey(leaf.getKey()) || !Objects.equals(was.get(leaf.getKey()), leaf.getValue())){
                    changes.add(new Leaf(entity.key(), leaf.getKey(), leaf.getValue()));
                }
            }
            for(String path : old.leaves().keySet()){
                if(!entity.leaves().containsKey(path)) changes.add(new LeafDrop(entity.key(), path));
            }
        }
        for(Key key : before.keySet()){
            if(!next.map().containsKey(key)) changes.add(new Drop(key));
        }
        frames.add(new Frame(next.tick(), List.copyOf(changes)));
        last = next;
    }

    int size(){
        return frames.size();
    }

    /** Replays the checkpoints in order; each {@link Flat} is new, sharing unchanged entities with the previous one. */
    Iterator<Flat> replay(){
        return new Iterator<>(){
            private int next;
            private TreeMap<Key, Entity> state = new TreeMap<>();

            @Override
            public boolean hasNext(){
                return next < frames.size();
            }

            @Override
            public Flat next(){
                if(!hasNext()) throw new NoSuchElementException();
                Frame frame = frames.get(next++);
                var map = new TreeMap<>(state);
                var edited = new HashMap<Key, LinkedHashMap<String, Object>>();
                for(Change change : frame.changes()){
                    switch(change){
                        case Put(Entity entity) -> {
                            map.put(entity.key(), entity);
                            edited.remove(entity.key());
                        }
                        case Drop(Key key) -> {
                            map.remove(key);
                            edited.remove(key);
                        }
                        case Leaf(Key key, String path, Object value) -> edit(map, edited, key).put(path, value);
                        case LeafDrop(Key key, String path) -> edit(map, edited, key).remove(path);
                    }
                }
                edited.forEach((key, leaves) -> {
                    Entity old = map.get(key);
                    map.put(key, new Entity(key, old.label(), old.kind(), leaves));
                });
                state = map;
                return new Flat(engine, frame.tick(), map, absent);
            }
        };
    }

    private static LinkedHashMap<String, Object> edit(TreeMap<Key, Entity> map, HashMap<Key, LinkedHashMap<String, Object>> edited, Key key){
        return edited.computeIfAbsent(key, k -> new LinkedHashMap<>(map.get(k).leaves()));
    }
}
