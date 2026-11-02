package com.xpdustry.shard.content.yaml;

import java.util.*;

/**
 * A read-only view of a {@link SequencedMap}: what {@code Collections.unmodifiableSequencedMap} returns. Every
 * mutator throws, entries included.
 */
final class ReadOnlyMap<K, V> extends AbstractMap<K, V> implements SequencedMap<K, V>{
    private final SequencedMap<K, V> map;
    private final Map<K, V> view;

    ReadOnlyMap(SequencedMap<K, V> map){
        this.map = map;
        view = Collections.unmodifiableMap(map);
    }

    @Override
    public Set<Entry<K, V>> entrySet(){
        return view.entrySet();
    }

    @Override
    public V get(Object key){
        return map.get(key);
    }

    @Override
    public boolean containsKey(Object key){
        return map.containsKey(key);
    }

    @Override
    public int size(){
        return map.size();
    }

    @Override
    public SequencedMap<K, V> reversed(){
        return new ReadOnlyMap<>(map.reversed());
    }
}
