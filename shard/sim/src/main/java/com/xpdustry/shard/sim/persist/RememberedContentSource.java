package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;

import java.lang.ref.*;
import java.util.*;

/**
 * Patched epochs made at runtime, which no pack names, by hash: a map's own content ({@code Epoch.patched}), which the
 * server makes when it loads the map. {@link ContentSource#remember} adds one; a checkpoint, crash bundle or replay of
 * a patched world taken in this process then resolves its content.
 *
 * <p>Bounds: keyed by hash, so loading the same map again adds nothing; and an epoch is held weakly, so it goes once
 * no world, config or checkpoint reader holds it (a hosted world's config holds its epoch while the world is hosted,
 * hibernated included). A server cycling through many patched maps keeps only those still in use. Stale entries are
 * dropped on the next {@link #remember}.
 *
 * <p>Another process has none of them: it rebuilds a patched world's epoch from the map's patches before it restores
 * (the server's {@code Host.prepare}), or {@link ContentSource#resolve} fails naming the hash.
 */
@Register
public final class RememberedContentSource implements ContentSource{
    /** By hash; a sorted map under this class's lock, as {@code :sim} keeps no hash-ordered collection (DeterminismTest). */
    private static final TreeMap<String, WeakReference<ContentEpoch>> REMEMBERED = new TreeMap<>();

    static synchronized void remember(ContentEpoch epoch){
        // only patches: a pack's epoch (vanilla's) resolves from its own source, never from one remembered here
        if(!(epoch instanceof Epoch e) || !e.isPatched()){
            throw new IllegalArgumentException(epoch.hash() + " is not a patched epoch; only a map's patched content is remembered");
        }
        REMEMBERED.values().removeIf(ref -> ref.get() == null);
        REMEMBERED.compute(epoch.hash(), (hash, old) -> old != null && old.get() != null ? old : new WeakReference<>(epoch));
    }

    /** Entries held, live or not yet dropped: for tests. */
    static synchronized int size(){
        return REMEMBERED.size();
    }

    @Override
    public ContentEpoch find(String hash){
        WeakReference<ContentEpoch> ref;
        synchronized(RememberedContentSource.class){
            ref = REMEMBERED.get(hash);
        }
        return ref == null ? null : ref.get();
    }
}
