package com.xpdustry.shard.testkit;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Bundled maps on Shard ({@link ShardRunner#maps}), through the real msav loader as the server's
 * {@code host <map> survival} loads them: the map's rules over survival's, every building's base read, unported kinds
 * passed through, the map's generation filters applied with the template's generator. What vanilla's host does beyond
 * that is not done: {@code logic.play()} (cores keep the map's items, the wave countdown is the map's). The template's kinds get
 * {@link com.xpdustry.shard.sim.building.KindRegistry#withPassthrough()}: a map holds every kind.
 */
public final class ShardMaps implements ShardRunner.MapLoader{
    private final MsavReader reader;
    private final MapLoader loader;

    private ShardMaps(Epoch epoch){
        reader = new MsavReader(epoch);
        loader = new MapLoader(epoch, BuildingCodecs.builtIn());
    }

    /** Maps loaded into worlds of {@code epoch}. */
    public static ShardMaps of(Epoch epoch){
        return new ShardMaps(epoch);
    }

    /** Maps loaded into worlds of {@link Epoch#vanilla()}. */
    public static ShardMaps vanilla(){
        return of(Epoch.vanilla());
    }

    @Override
    public World load(String map, WorldConfig template){
        return host(map, template).world();
    }

    /** {@link #load}, with the state the world stream needs beside the world. */
    public MapLoader.Loaded host(String map, WorldConfig template){
        SaveFile save = reader.read(BundledMaps.read(map));
        return loader.loadMap(save, template.withKinds(template.kinds().withPassthrough()), loader.hostRules(save, Gamemode.SURVIVAL));
    }
}
