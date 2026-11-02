package com.xpdustry.shard.server.config;

import com.xpdustry.shard.server.config.Config.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * The current config generation, and reloads. A reload validates a whole candidate first; if it fails, nothing
 * changes. If it passes, its {@link ConfigKey.Reload#LIVE LIVE} changes take effect at once and its
 * {@link ConfigKey.Reload#RESTART RESTART} changes stay pending: the effective config keeps the old values for those,
 * and {@link #pending()} lists them until a restart, so nothing claims to apply what it didn't (note 34).
 *
 * <p>Services read {@link #current()} when they need a value, or {@link #subscribe} to hear about changes. A world
 * applies its changes at its next tick boundary; this class only publishes them.
 */
public final class ConfigService{
    private final Loader loader;
    private final AtomicReference<State> state;
    private final List<Consumer<Reloaded>> listeners = new CopyOnWriteArrayList<>();

    /** Loads the first generation; a bad config fails the startup. */
    public ConfigService(Loader loader) throws ConfigException{
        this.loader = loader;
        this.state = new AtomicReference<>(new State(1, loader.load(), List.of()));
    }

    /** Where candidates come from: normally {@code () -> ConfigLoader.loadDirectory(dir, System.getenv())}. */
    @FunctionalInterface
    public interface Loader{
        Config load() throws ConfigException;
    }

    public Config current(){
        return state.get().config;
    }

    /** The generation of {@link #current()}: 1 at startup, one more per reload that changed something. */
    public long generation(){
        return state.get().generation;
    }

    /** Changes a restart would apply. */
    public List<Change> pending(){
        return state.get().pending;
    }

    public void subscribe(Consumer<Reloaded> listener){
        listeners.add(listener);
    }

    /**
     * Loads and validates a candidate, then publishes it. Throws, leaving the current generation alone, if the
     * candidate doesn't validate. Reloads are serialized.
     */
    public synchronized Reloaded reload() throws ConfigException{
        Config candidate = loader.load();
        State before = state.get();
        var applied = new ArrayList<Change>();
        var pending = new ArrayList<Change>();

        // host values
        var host = new LinkedHashMap<ConfigKey<?>, Entry>();
        for(var key : ConfigKeys.ALL){
            Entry old = before.config.hostEntries().get(key), next = candidate.hostEntries().get(key);
            host.put(key, decide(key, old, next, applied, pending));
        }

        // world overrides, for every world either generation mentions
        var ids = new TreeSet<String>(before.config.worldEntries().keySet());
        ids.addAll(candidate.worldEntries().keySet());
        var worlds = new TreeMap<String, Map<ConfigKey<?>, Entry>>();
        for(String id : ids){
            var oldView = before.config.world(id);
            var nextView = new Config(host, candidate.worldEntries()).world(id);
            var entries = new LinkedHashMap<ConfigKey<?>, Entry>();
            for(var key : ConfigKeys.ALL){
                if(key.scope() != ConfigKey.Scope.WORLD) continue;
                Object old = oldView.entry(key).value(), next = nextView.entry(key).value();
                boolean changed = !Objects.equals(old, next);
                boolean keepOld = changed && key.reload() == ConfigKey.Reload.RESTART;
                // a host change already has its own entry; list the world only when its override is involved
                if(changed && hasOverride(before.config, candidate, id, key)){
                    (keepOld ? pending : applied).add(new Change(id, key, old, next));
                }
                var override = (keepOld ? before.config : candidate).worldEntries().getOrDefault(id, Map.of()).get(key);
                if(override != null) entries.put(key, override);
            }
            if(!entries.isEmpty()) worlds.put(id, Collections.unmodifiableMap(entries));
        }

        var config = new Config(Collections.unmodifiableMap(host), Collections.unmodifiableMap(worlds));
        long generation = applied.isEmpty() ? before.generation : before.generation + 1;
        var after = new State(generation, config, List.copyOf(pending));
        state.set(after);
        var reloaded = new Reloaded(generation, List.copyOf(applied), after.pending);
        if(!applied.isEmpty()) for(var listener : listeners) listener.accept(reloaded);
        return reloaded;
    }

    private static boolean hasOverride(Config before, Config candidate, String id, ConfigKey<?> key){
        return before.worldEntries().getOrDefault(id, Map.of()).containsKey(key)
            || candidate.worldEntries().getOrDefault(id, Map.of()).containsKey(key);
    }

    /** The host entry the new generation uses for {@code key}; records the change as applied or pending. */
    private static Entry decide(ConfigKey<?> key, Entry old, Entry next, List<Change> applied, List<Change> pending){
        if(Objects.equals(old.value(), next.value())) return next; // same value, maybe a new source
        if(key.reload() == ConfigKey.Reload.RESTART){
            pending.add(new Change(null, key, old.value(), next.value()));
            return old;
        }
        applied.add(new Change(null, key, old.value(), next.value()));
        return next;
    }

    private record State(long generation, Config config, List<Change> pending){
    }

    /**
     * One key whose value changed.
     *
     * @param world the world whose view changed, or null for the host value
     */
    public record Change(String world, ConfigKey<?> key, Object before, Object after){
        @Override
        public String toString(){
            return (world == null ? "" : "worlds." + world + ": ") + key.path() + " " + key.display(before) + " -> " + key.display(after);
        }
    }

    /**
     * The result of a reload.
     *
     * @param applied the changes now in effect; empty when nothing changed
     * @param pending every change still waiting for a restart, including ones from earlier reloads
     */
    public record Reloaded(long generation, List<Change> applied, List<Change> pending){
    }
}
