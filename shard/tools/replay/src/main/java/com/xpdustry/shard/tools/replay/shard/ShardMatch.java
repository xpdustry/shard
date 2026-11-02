package com.xpdustry.shard.tools.replay.shard;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.util.*;

/**
 * A scripted Shard match on a bundled map: the map loaded with {@code compat.v160.msav}, and every
 * {@value #SQUAD_EVERY} ticks a {@link SpawnSquad} command per core, submitted through the inbox as any input is.
 */
final class ShardMatch{
    static final int SQUAD_EVERY = 600, SQUAD = 8, MAX_UNITS = 240;

    final String map;
    final World world;
    final List<Integer> cores = new ArrayList<>();

    ShardMatch(String map) throws IOException{
        this.map = map;
        Epoch epoch = Epoch.vanilla();
        SaveFile save = new MsavReader(epoch).read(read(map));
        var loader = new MapLoader(epoch, BuildingCodecs.builtIn());
        Rules rules = loader.hostRules(save, Gamemode.SURVIVAL);
        rules.disableUnitCap = true;
        world = loader.loadMap(save, WorldConfig.of(epoch, 1, 1, 1).withKinds(KindRegistry.builtIn().withPassthrough()), rules).world();
        world.buildings().each(build -> {
            if(build.block().name().startsWith("core-")) cores.add(build.tile());
        });
        if(cores.size() < 2) throw new IllegalStateException(map + " has " + cores.size() + " cores; the skirmish needs two teams' cores");
    }

    /** Submits this tick's inputs, if any are due; call before {@code world.tick()}. */
    void inputs(){
        if(world.ticks() % SQUAD_EVERY != 0 || world.units().size() >= MAX_UNITS) return;
        for(int core : cores) world.submit(new SpawnSquad(core, SQUAD), new Cause.Server());
    }

    static byte[] read(String name) throws IOException{
        String path = name.contains("/") ? "maps/" + name + ".msav" : "maps/default/" + name + ".msav";
        try(InputStream in = ShardMatch.class.getClassLoader().getResourceAsStream(path)){
            if(in == null) throw new FileNotFoundException(path + " is not in the vanilla jar");
            return in.readAllBytes();
        }
    }
}
