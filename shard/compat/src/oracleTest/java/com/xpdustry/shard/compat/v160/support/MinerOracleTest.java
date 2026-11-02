package com.xpdustry.shard.compat.v160.support;

import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.unit.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for mining (WP45) and {@code MinerAI} (WP70): monos on Ground Zero, hosted for survival by vanilla and Shard,
 * compared frame by frame ({@link SupportRun}) for 1,800 frames, Calls included. Each mono runs the {@code mine}
 * command's AI: it picks the item the core holds least of, flies to the ore nearest the core, mines a full stack and
 * brings it back. Two start with part of a stack so they deposit early. The second test saves the world in vanilla
 * mid-mine, loads the save on both sides, and goes on.
 */
class MinerOracleTest{
    static final int FRAMES = 1_800;

    @Test
    void monosMineCopperAndLeadIntoTheCore(){
        var run = monos();
        var mined = new TreeSet<String>();
        int[] mining = {0};
        run.until(FRAMES + 1, () -> {
            watch(run, mined, mining);
            return run.frame >= FRAMES;
        });
        assertMined(run, mined, mining[0]);
    }

    @Test
    void aMonoSavedMidMineGoesOnAsVanilla(){
        var run = monos();
        var mined = new TreeSet<String>();
        int[] mining = {0};
        run.until(FRAMES + 1, () -> {
            watch(run, mined, mining);
            return run.frame >= 700;
        });
        boolean midMine = false;
        for(int i = 0; i < run.shard.units().size(); i++){
            Unit unit = run.shard.units().get(i);
            midMine |= unit.mineTile != -1 && unit.mineTimer > 0f && unit.stackAmount > 0;
        }
        assertTrue(midMine, "a mono is mid-mine, with items, when the world is saved");
        run.saveAndLoad();
        run.until(FRAMES + 1, () -> {
            watch(run, mined, mining);
            return run.frame >= FRAMES;
        });
        assertMined(run, mined, mining[0]);
    }

    private static SupportRun monos(){
        var run = new SupportRun("serpulo/groundZero");
        float cx = run.coreX * 8f, cy = run.coreY * 8f;
        run.at(1, () -> {
            run.spawn("mono", cx + 24f, cy, null, 0);
            run.spawn("mono", cx - 24f, cy + 8f, "lead", 12);
            run.spawn("mono", cx, cy + 32f, "copper", 19);
        });
        return run;
    }

    /** Records which items Shard's miners targeted, and how many frames a mono was mining. */
    private static void watch(SupportRun run, Set<String> mined, int[] mining){
        for(int i = 0; i < run.shard.units().size(); i++){
            Unit unit = run.shard.units().get(i);
            if(unit.controller instanceof CommandAi ai && ai.commandController instanceof MinerAi miner && miner.targetItem != -1){
                mined.add(SupportRun.EPOCH.item(miner.targetItem).name());
            }
            if(unit.mineTile != -1 && unit.mineTimer > 0f) mining[0]++;
        }
    }

    private static void assertMined(SupportRun run, Set<String> mined, int mining){
        var core = world.build(run.coreX, run.coreY);
        int copper = core.items.get(content.item("copper")), lead = core.items.get(content.item("lead"));
        System.out.println("monos on Ground Zero: " + run.frame + " frames; core copper " + copper + ", lead " + lead + "; targets " + mined
            + "; mono-frames mining " + mining + "; Calls " + run.calls);
        assertEquals(Set.of("copper", "lead"), mined, "the monos mined both");
        assertTrue(copper > 19 && lead > 12, "the core got more than the stacks they started with: copper " + copper + ", lead " + lead);
        assertTrue(run.calls.getOrDefault("TransferItemTo", 0) >= 4, "stacks went into the core: " + run.calls);
        assertTrue(mining > 1_000, "the monos spent their time mining: " + mining);
    }
}
