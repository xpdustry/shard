package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.logic.LogicPart;
import com.xpdustry.shard.sim.team.Teams;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * {@code NetServer.writeStateSnapshot} ({@code NetServer.java:1130}): the game state every client reads 5 times a
 * second ({@code NetClient.stateSnapshot}). The core data is a {@code u8} count of the present teams that have a core,
 * then for each, in team order, its id and its first core's {@code ItemModule.write}: a {@code u16} count of the items
 * it holds, then each as {@code u16} id and {@code i32} amount, in id order.
 *
 * @param wavetime  {@code state.wavetime}, in ticks
 * @param wave      {@code state.wave}
 * @param enemies   {@code state.enemies}
 * @param paused    {@code state.isPaused()}
 * @param gameOver  {@code state.gameOver}
 * @param seconds   {@code universe.seconds()}: only a campaign advances it, so a server sends the value it loaded
 *                  ({@code Universe.load}, the {@code utimei} setting)
 * @param tps       the server's frames per second, at most 255
 * @param rand0     {@code GlobalVars.rand.seed0}: logic's {@code op rand} generator as it is now
 * @param rand1     {@code GlobalVars.rand.seed1}
 * @param cores     the present teams with a core, in id order, and their first core's items by item id
 */
public record GameState(float wavetime, int wave, int enemies, boolean paused, boolean gameOver, int seconds, int tps,
                             long rand0, long rand1, List<Core> cores){
    public GameState{
        cores = List.copyOf(cores);
    }

    /** The tick rate a world that keeps up runs at. */
    public static final int TPS = 60;

    /**
     * The state of {@code world} now: the wave, its countdown, the enemy count and {@code gameOver} from its
     * {@link WaveState}; each present team's first core and its items ({@link Teams}); the logic random as it is now
     * ({@link LogicPart#rand}, which a client's {@code op rand} continues from), and {@code seconds}, the universe time
     * the server loaded; {@code paused} is {@link World#paused()}. A world without a {@code WaveState} reports the map's wave, and one
     * without a {@code LogicPart} the map's random.
     */
    public static GameState of(World world, MapState state, int tps, int seconds){
        List<Core> cores = cores(world);
        LogicPart logic = LogicPart.of(world);
        Rand rand = logic == null ? new Rand(state.seed0, state.seed1) : logic.rand();
        WaveState waves = WaveState.of(world);
        if(waves == null) return new GameState(state.wavetime, state.wave, 0, world.paused(), false, seconds, tps, rand.seed0, rand.seed1, cores);
        return new GameState(waves.wavetime, waves.wave, waves.enemies, world.paused(), waves.gameOver, seconds, tps, rand.seed0, rand.seed1, cores);
    }

    /** {@code state.teams.present} with a core, in order, and their first core's items. */
    private static List<Core> cores(World world){
        Teams teams = Teams.of(world);
        if(teams == null) return List.of();
        var out = new ArrayList<Core>();
        IntSeq present = TeamPresence.present(world);
        for(int i = 0; i < present.size; i++){
            var core = teams.core(present.get(i));
            if(core == null) continue;
            int[] items = new int[core.items.length()];
            for(int item = 0; item < items.length; item++) items[item] = core.items.get(item);
            out.add(new Core(present.get(i), items));
        }
        return out;
    }

    /** A team's first core: its items by item id. */
    public record Core(int team, int[] items){
        public Core{
            items = items.clone();
        }
    }

    public Packets.StateSnapshot packet(){
        var out = new Writes(1 + cores.size() * 64);
        out.b(cores.size());
        for(Core core : cores){
            out.b(core.team());
            int[] items = core.items();
            int held = 0;
            for(int amount : items) if(amount > 0) held++;
            out.s(held);
            for(int id = 0; id < items.length; id++){
                if(items[id] > 0){
                    out.s(id);
                    out.i(items[id]);
                }
            }
        }
        return new Packets.StateSnapshot(wavetime, wave, enemies, paused, gameOver, seconds, (byte)Math.min(tps, 255), rand0, rand1,
            out.toByteArray());
    }
}
