package com.xpdustry.shard.sim.game;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;


/**
 * Vanilla's {@code Logic.play()} (Logic.java:269) on a world that just loaded a map, with the rules the game plays
 * with: what {@code host} does after loading, in vanilla's order.
 * <ol>
 *   <li>The wave countdown starts at {@code initialWaveSpacing}, or twice {@code waveSpacing} when that is not
 *   positive ({@link WaveState#play}).</li>
 *   <li>{@code PlayEvent}: the weather entries, in a shuffled order, get cooldowns that add up ({@link Weathers#play}),
 *   and {@code state.tick} goes back to 0, which the caller keeps.</li>
 *   <li>Each active team's first core holds the loadout and nothing else, each stack capped at the core's free
 *   space.</li>
 *   <li>Every core of an active team is healed, which publishes its health update.</li>
 * </ol>
 * The campaign isn't hosted: there, vanilla scales the countdown by the planet's difficulty and may keep the cores'
 * items, and this is refused.
 */
public final class Play{
    private Play(){
    }

    /** What {@link #play} set: {@code state.wavetime}, which {@link WaveState} holds too, and {@code state.tick}, reset. */
    public record Started(float wavetime, double tick){
    }

    /** {@code logic.play()} on {@code world}. */
    public static Started play(World world){
        Rules rules = UnitCalls.rules(world);
        if(UnitCalls.isCampaign(rules)) throw new IllegalStateException("the campaign isn't hosted: sector " + rules.sector);
        WaveState waves = WaveState.of(world);
        float wavetime;
        if(waves != null){
            waves.play(rules);
            wavetime = waves.wavetime;
        }else{
            wavetime = rules.initialWaveSpacing <= 0 ? rules.waveSpacing * 2 : rules.initialWaveSpacing;
        }
        Weathers.play(world);
        Teams teams = Teams.of(world);
        if(teams != null){
            teams.play();
            IntSeq active = teams.getActive(world);
            for(int i = 0; i < active.size; i++){
                Core core = teams.core(active.get(i));
                if(core == null) continue;
                core.items.clear();
                for(ItemStack stack : rules.loadout){
                    // make sure to cap storage
                    core.items.add(stack.item(), Math.min(stack.amount(), core.storageCapacity - core.items.get(stack.item())));
                }
            }
            active = teams.getActive(world);
            for(int i = 0; i < active.size; i++){
                for(Core core : teams.cores(active.get(i))) core.heal();
            }
        }
        return new Started(wavetime, 0.0);
    }
}
