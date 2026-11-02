package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for W2.17's move of {@code HashTest.GOLDEN} (review 40): without W2.17's parts,
 * {@link AvoidanceProcess} and {@link WaveFields}, the scripted world hashes to main's golden from before W2.17, and with
 * them only their own two sections differ. Their handlers do nothing without them.
 *
 * <p>That is all the AI holds here. The scripted world's units are the toy's drones, steered by the test's
 * {@code Follow}: the type's controller a unit gets when created ({@code Controllers.create}, a wave AI) is replaced at
 * once, and its construction draws as an inert one would (the clock section is the same). So W2.17's scratch toggle,
 * which also made every AI inert and moved its timers to the tick count, changes nothing more here; the proof that the
 * AIs behave as vanilla's is their oracle tests'.
 */
class AiHashToggleTest{
    /** {@code HashTest.GOLDEN} at {@code 994d1ea8}, the merge before W2.17's. */
    static final long GOLDEN_BEFORE_AI = 0x57ca30d624dd9d58L;
    /** What W2.17 changes in the scripted world: its two parts' sections. */
    static final SortedSet<String> AI_SECTIONS = new TreeSet<>(List.of(
        AvoidanceProcess.class.getName(), WaveFields.class.getName()));

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    /**
     * Parts that came after W2.17, left out of both sides: W2.23's LogicUnits (LogicUnitsHashToggleTest), W3.1's
     * DriverBolts (DriverBoltsHashToggleTest), W3.7's OreIndex (OreIndexHashToggleTest), W3.6's RegenMends
     * (RegenMendsHashToggleTest), W3.12's ObjectiveState (ObjectiveStateHashToggleTest) and W4.3's FogControl
     * (FogHashToggleTest).
     */
    static final Set<Class<? extends WorldPart>> LATER = Set.of(com.xpdustry.shard.sim.logic.LogicUnits.class,
        com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class, com.xpdustry.shard.sim.unit.OreIndex.class,
        com.xpdustry.shard.sim.blocks.defense.RegenMends.class, com.xpdustry.shard.sim.objectives.ObjectiveState.class,
        com.xpdustry.shard.sim.fog.FogControl.class);

    static Systems without(Set<Class<? extends WorldPart>> excluded){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class) if(!excluded.contains(part)) parts.add(part);
        // the parts' handlers do nothing without them
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    static Systems withAi(){
        return without(LATER);
    }

    static Systems withoutAi(){
        var excluded = new HashSet<Class<? extends WorldPart>>(LATER);
        excluded.add(AvoidanceProcess.class);
        excluded.add(WaveFields.class);
        return without(excluded);
    }

    /** The sections that differ between the two hashes, or that only one has. */
    static SortedSet<String> differing(WorldHash with, WorldHash without){
        var by = new TreeMap<String, Long>();
        for(WorldHash.Section section : without.sections()) by.put(section.name(), section.hash());
        var out = new TreeSet<String>();
        for(WorldHash.Section section : with.sections()){
            Long other = by.remove(section.name());
            if(other == null || other != section.hash()) out.add(section.name());
        }
        out.addAll(by.keySet());
        return out;
    }

    /** The proof: the sections that differ but aren't the AI's. Empty when it holds. */
    static SortedSet<String> unexplained(WorldHash with, WorldHash without){
        var out = differing(with, without);
        out.removeAll(AI_SECTIONS);
        return out;
    }

    @Test
    void withoutTheAiTheGoldenIsTheOneBeforeIt(){
        long value = com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutAi())).value();
        assertEquals(GOLDEN_BEFORE_AI, value, String.format("0x%016xL", value));
    }

    @Test
    void theAiChangesOnlyItsOwnSections(){
        WorldHash with = churned(withAi()).hash(), without = churned(withoutAi()).hash();
        assertEquals(new TreeSet<String>(), unexplained(with, without), "differing: " + differing(with, without));
        assertEquals(AI_SECTIONS, differing(with, without), "every named section moved");
    }

    @Test
    void anUnrelatedChangeFailsTheProof(){
        World with = churned(withAi());
        WorldHash without = churned(withoutAi()).hash();
        assertEquals(new TreeSet<String>(), unexplained(with.hash(), without));
        // a tile's floor, which no AI writes
        with.tiles().floor(0, with.tiles().floor(0) + 1);
        assertEquals(new TreeSet<>(List.of("tiles")), unexplained(with.hash(), without));
    }
}
