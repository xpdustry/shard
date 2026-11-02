package com.xpdustry.shard.sim.ai.team;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for W5.2's move of {@code HashTest.GOLDEN}: every world now has {@link TeamAis}, which the toy world
 * (no team with {@code rtsAi} or {@code buildAi}) leaves empty. Without it the scripted world hashes to main's golden at
 * {@code 0171c893}, and with it only its own section is new. The hooks that feed it (bullet damage, {@code estimateDps})
 * change nothing else here: the RTS AI is the only listener, and nothing else reads the estimates.
 *
 * <p>The other toggle tests leave this part out of both sides through {@link #builtInBefore()} (or their own lists).
 */
public class TeamAisHashToggleTest{
    /** {@code HashTest.GOLDEN} at {@code 0171c893}, main as W5.2 started from it. */
    static final long MAINS_GOLDEN = 0x07772ddc050b4c25L;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    /** {@code Systems.builtIn()} without {@link TeamAis}: what the toggle tests before W5.2 compare against. */
    public static Systems builtInBefore(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != TeamAis.class) parts.add(part);
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    @Test
    void withoutTheTeamAisTheGoldenIsMains(){
        long value = com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(builtInBefore())).value();
        assertEquals(MAINS_GOLDEN, value, String.format("0x%016xL", value));
    }

    @Test
    void theTeamAisAddOnlyTheirOwnSection(){
        WorldHash with = churned(Systems.builtIn()).hash(), without = churned(builtInBefore()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(TeamAis.class.getName())), "the team AIs' section");
        assertEquals(expected, actual);
    }
}
