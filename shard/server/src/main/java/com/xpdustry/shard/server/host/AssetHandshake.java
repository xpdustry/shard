package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.content.patch.DataAsset.*;
import com.xpdustry.shard.server.session.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The join's asset handshake of a world with external assets (a map's sprites; {@link MapState#assets}), as vanilla's
 * {@code NetServer} runs it (NetServer.java:330, 955-993):
 * <ol>
 *   <li>a join or a map change ({@code sendWorldAndAssets}): the {@code AssetRequirementStream} (stream 4) with every
 *   external asset's hash, and the load waits, determining;</li>
 *   <li>{@code requestAssets(ids)}, honoured once per load and only while determining: empty, the world is sent at
 *   once; else the {@code AssetStream} (stream 5) with the assets those ids name, out-of-range ids and repeats
 *   skipped, and the load waits, receiving;</li>
 *   <li>{@code requestWorld}, honoured only while receiving: the world is sent.</li>
 * </ol>
 * Anything out of that order is ignored, as vanilla's flags ignore it. {@code /sync} sends the world alone, as
 * vanilla's does.
 *
 * <p>Bounds on what a client can make the server do, since {@code requestAssets} is untrusted: one asset stream per
 * load, each asset in it at most once, so at most the map's own assets; the ids are a packet's worth
 * (16,381 at most in a 32 KiB frame) and are scanned once; the stream is written off the tick threads; the
 * connection's stream cap closes a client that doesn't read it ({@code Conn#sendStream}); and a load that never
 * finishes the handshake is kicked by the load timeout, as one that never confirms is.
 *
 * <p>Thread-safe: {@link #begin}, {@link #ready} and {@link #forget} run on the tick thread, the requests on the
 * transport's.
 */
final class AssetHandshake{
    private enum Phase{
        DETERMINING, RECEIVING, DONE
    }

    /** One load's handshake. */
    record Pending(Session session, Session.Load load, AtomicReference<Phase> phase){
        boolean advance(Phase from, Phase to){
            return phase.compareAndSet(from, to);
        }
    }

    /** What a {@code requestAssets} asks of the world. */
    sealed interface Answer{
    }

    /** Out of order, stale or repeated: nothing to do. */
    record Ignored() implements Answer{
    }

    /** The client has every asset: send the world. */
    record SendWorld() implements Answer{
    }

    /** Send these assets as the {@code AssetStream} of {@code load}, then wait for {@code requestWorld}. */
    record SendAssets(Session.Load load, List<ExternalAsset> assets) implements Answer{
    }

    static final Answer IGNORED = new Ignored(), SEND_WORLD = new SendWorld();

    private final DataAssets assets;
    private final byte[] requirements;
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    /** Loads whose world was asked for: the tick thread sends them. */
    private final ConcurrentLinkedQueue<Pending> ready = new ConcurrentLinkedQueue<>();

    private AssetHandshake(DataAssets assets){
        this.assets = assets;
        this.requirements = AssetStreams.requirements(assets);
    }

    /** The handshake of a world whose map has {@code assets}, or null if it has no external ones: its world goes out at once. */
    static AssetHandshake of(DataAssets assets){
        return assets == null || assets.externals().isEmpty() ? null : new AssetHandshake(assets);
    }

    /** The requirement stream every join gets ({@code NetworkIO.writeRequiredAssets}, deflated). */
    byte[] requirements(){
        return requirements;
    }

    /** Tick thread: {@code sendAssetRequirements} for {@code load}; any older handshake of the session is replaced. */
    void begin(Session session, Session.Load load){
        pending.put(session.id(), new Pending(session, load, new AtomicReference<>(Phase.DETERMINING)));
        session.streamAside(load, AssetStreams.REQUIREMENT_STREAM, requirements);
    }

    /** {@code requestAssets(ids)} for the session's current {@code load}. Any thread. */
    Answer requestAssets(Session session, Session.Load load, short[] ids){
        Pending current = current(session, load);
        if(current == null || !current.advance(Phase.DETERMINING, Phase.RECEIVING)) return IGNORED;
        if(ids.length == 0){
            // all cached (NetServer.java:969)
            if(current.advance(Phase.RECEIVING, Phase.DONE)) ready.add(current);
            return SEND_WORLD;
        }
        return new SendAssets(load, AssetStreams.requested(assets, ids));
    }

    /** {@code requestWorld} for the session's current {@code load}: whether the world is now to be sent. Any thread. */
    boolean requestWorld(Session session, Session.Load load){
        Pending current = current(session, load);
        if(current == null || !current.advance(Phase.RECEIVING, Phase.DONE)) return false;
        ready.add(current);
        return true;
    }

    private Pending current(Session session, Session.Load load){
        Pending current = pending.get(session.id());
        return current != null && current.session() == session && current.load() == load && session.load() == load ? current : null;
    }

    /** Tick thread: the loads whose world was asked for since the last call, oldest first; each is forgotten. */
    List<Pending> ready(){
        if(ready.isEmpty()) return List.of();
        var out = new ArrayList<Pending>();
        for(Pending next; (next = ready.poll()) != null; ){
            pending.remove(next.session().id(), next);
            out.add(next);
        }
        return out;
    }

    /** Whether a world was asked for and waits for the tick thread. */
    boolean hasReady(){
        return !ready.isEmpty();
    }

    /** Loads still in the handshake. */
    int waiting(){
        return pending.size();
    }

    /** Tick thread: the session left; its handshake, if any, ends. */
    void forget(long session){
        pending.remove(session);
    }

    /** Tick thread: the world starts over (attached, failed). */
    void clear(){
        pending.clear();
        ready.clear();
    }
}
