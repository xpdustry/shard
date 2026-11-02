package com.xpdustry.shard.sim.ai.command;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for W4.2's move of {@code HashTest.GOLDEN}: every world now has {@link CommandBatches} (vanilla's
 * {@code queuedCommands}), which the toy world leaves empty. Without it the scripted world hashes to main's golden at
 * {@code f02581f8}, and with it only its own section is new. The toy world has no command AI, so W4.2's other hashed
 * state ({@code CommandAi}'s new fields, {@link UnitGroup}) doesn't reach this hash; the command oracles hold it.
 *
 * <p>The other toggle tests leave this part out of both sides through {@link #builtInBefore()}.
 */
public class CommandBatchesHashToggleTest{
    /** {@code HashTest.GOLDEN} at {@code f02581f8}, main as W4.2 last merged it (W4.3's fog included). */
    static final long MAINS_GOLDEN = 0xc6b64a5e94e08c4eL;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    /** {@code Systems.builtIn()} without {@link CommandBatches}: what the toggle tests before W4.2 compare against. */
    public static Systems builtInBefore(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class) parts.add(part);
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    @Test
    void withoutTheBatchesTheGoldenIsMains(){
        long value = com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(builtInBefore())).value();
        assertEquals(MAINS_GOLDEN, value, String.format("0x%016xL", value));
    }

    @Test
    void theBatchesAddOnlyTheirOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.team.TeamAisHashToggleTest.builtInBefore()).hash(), without = churned(builtInBefore()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(CommandBatches.class.getName())), "the batches' section");
        assertEquals(expected, actual);
    }
}
