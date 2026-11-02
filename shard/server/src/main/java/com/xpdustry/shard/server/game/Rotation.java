package com.xpdustry.shard.server.game;

import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.config.ConfigKeys.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.rules.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Each world's next map: vanilla's {@code Maps.getNextMap} (Maps.java:74, {@code ShuffleMode} 495-525) over the
 * world's own registry. A {@link #next override} ({@code nextmap}) goes first and is used once; otherwise the world's
 * shuffle mode ({@code game.shuffle}, or the console's {@code shuffle}) picks: none picks nothing, and all, custom and
 * builtin shuffle their maps among {@code game.maps} (every hostable map when empty) and take the first that isn't
 * the map just played, unless it is the only one, and suits the mode: the PvP maps ({@code veins}, {@code glacier},
 * {@code passage}) only in PvP, where custom maps go too.
 *
 * <p>The shuffle draws from a random that isn't world state, as vanilla's {@code Seq.shuffle} draws from
 * {@code Mathf.rand}, but each world has its own, seeded from the server's seed and the world's id: a world's
 * sequence of maps is the same whatever the other worlds play. Thread-safe.
 */
public final class Rotation{
    /** Vanilla's {@code Maps.pvpMaps}. */
    static final Set<String> PVP_MAPS = Set.of("veins", "glacier", "passage");

    private final Host host;
    private final long seed;
    /** Each world's shuffle random, made on its first draw. */
    private final ConcurrentHashMap<WorldId, Random> randoms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorldId, MapCatalog.Entry> overrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorldId, Shuffle> shuffles = new ConcurrentHashMap<>();

    /** @param seed the server's: with the same seed, each world draws the same maps */
    public Rotation(Host host, long seed){
        this.host = host;
        this.seed = seed;
    }

    public long seed(){
        return seed;
    }

    /** {@code setNextMapOverride}: the map world {@code id} plays after its next game over, once. */
    public void next(WorldId id, MapCatalog.Entry map){
        overrides.put(id, Objects.requireNonNull(map, "map"));
    }

    /** The console's {@code shuffle}: a mode that replaces {@code game.shuffle} for {@code id} until restart. */
    public void shuffle(WorldId id, Shuffle mode){
        shuffles.put(id, Objects.requireNonNull(mode, "mode"));
    }

    public Shuffle shuffle(WorldId id){
        Shuffle mode = shuffles.get(id);
        return mode != null ? mode : host.config(id).get(ConfigKeys.GAME_SHUFFLE);
    }

    /** {@code getNextMap(mode, previous)} for {@code world}: the map it plays next, or null to end. */
    public MapCatalog.Entry next(HostedWorld world){
        return next(world.id(), world.map(), world.mode());
    }

    /** {@link #next(HostedWorld)} for world {@code id}, which played {@code prev} in {@code mode}. */
    MapCatalog.Entry next(WorldId id, MapCatalog.Entry prev, Gamemode mode){
        MapCatalog.Entry override = overrides.remove(id);
        if(override != null) return override;
        List<MapCatalog.Entry> registry = registry(id);
        List<MapCatalog.Entry> pool = switch(shuffle(id)){
            case NONE -> List.of();
            case ALL -> concat(registry.stream().filter(m -> !m.custom()).toList(), registry.stream().filter(MapCatalog.Entry::custom).toList());
            case CUSTOM -> {
                List<MapCatalog.Entry> custom = registry.stream().filter(MapCatalog.Entry::custom).toList();
                yield custom.isEmpty() ? registry.stream().filter(m -> !m.custom()).toList() : custom;
            }
            case BUILTIN -> registry.stream().filter(m -> !m.custom()).toList();
        };
        return pick(pool, prev, mode, random(id));
    }

    /** World {@code id}'s random: the server's seed mixed with the id's hash. */
    Random random(WorldId id){
        return randoms.computeIfAbsent(id, key -> new Random(Stagger.mix(seed ^ key.hash())));
    }

    /** {@code ShuffleMode.next(mode, prev, maps)}: shuffled, the first that isn't {@code prev} (unless alone) and suits the mode. */
    static MapCatalog.Entry pick(List<MapCatalog.Entry> maps, MapCatalog.Entry prev, Gamemode mode, Random random){
        var shuffled = new ArrayList<>(maps);
        Collections.shuffle(shuffled, random);
        for(MapCatalog.Entry map : shuffled){
            if((!map.file().equals(prev.file()) || shuffled.size() == 1) && valid(mode, map)) return map;
        }
        return null;
    }

    /** {@code ShuffleMode.valid(mode, map)}. */
    static boolean valid(Gamemode mode, MapCatalog.Entry map){
        boolean pvp = !map.custom() && PVP_MAPS.contains(map.file());
        if(mode == Gamemode.SURVIVAL || mode == Gamemode.ATTACK || mode == Gamemode.SANDBOX) return !pvp;
        if(mode == Gamemode.PVP) return map.custom() || pvp;
        return true;
    }

    /** {@code game.maps} as catalog entries, in order; every hostable map when it is empty. */
    List<MapCatalog.Entry> registry(WorldId id){
        List<String> names = host.config(id).get(ConfigKeys.GAME_MAPS);
        if(names.isEmpty()) return host.maps().all();
        var out = new ArrayList<MapCatalog.Entry>(names.size());
        for(String name : names){
            MapCatalog.Entry map = host.maps().find(name).orElse(null);
            if(map != null && !out.contains(map)) out.add(map);
        }
        return out;
    }

    private static <T> List<T> concat(List<T> a, List<T> b){
        var out = new ArrayList<T>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }
}
