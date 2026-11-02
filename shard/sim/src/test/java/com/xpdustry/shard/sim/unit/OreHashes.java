package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * For toggle proofs pinned to a golden from before W3.7: W3.7 moved {@code LogicUnits}' floor-ore half into
 * {@link OreIndex}, so a world's hash as it was then has {@code LogicUnits}' section fed as it was, its timeouts, then
 * that half, then {@code fetch}'s building lists. The half is the index's floor lists, which are the same
 * ({@code OreIndexHashToggleTest}).
 */
public final class OreHashes{
    public static final String LOGIC_UNITS = "com.xpdustry.shard.sim.logic.LogicUnits";

    private OreHashes(){
    }

    @SuppressWarnings("unchecked")
    static <T> T field(Object owner, String name){
        try{
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T)field.get(owner);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /** {@code LogicUnits.hash} before W3.7, for {@code world}, which runs no processor (no timeouts), with {@code ores}' lists. */
    public static long logicUnitsBefore(World world, OreIndex ores){
        boolean loaded = field(ores, "loaded");
        int quadWidth = field(ores, "quadWidth"), quadHeight = field(ores, "quadHeight");
        IntSeq[][] lists = field(ores, "ores");
        var h = new Hasher();
        h.i(0);
        h.bool(loaded).i(quadWidth).i(quadHeight);
        for(int item = 0; lists != null && item < lists.length; item++){
            if(lists[item] == null) continue;
            for(int q = 0; q < lists[item].length; q++){
                IntSeq list = lists[item][q];
                if(list != null) h.i(item).i(q).ints(list.items, 0, list.size);
            }
        }
        world.buildings().hashTeamLists(h);
        return h.value();
    }

    /** {@code world}'s hash with {@code LogicUnits}' section as it was before W3.7, from {@code ores}' lists. */
    public static WorldHash before(World world, OreIndex ores){
        long logicUnits = logicUnitsBefore(world, ores);
        var sections = new ArrayList<WorldHash.Section>();
        for(WorldHash.Section section : world.hash().sections()){
            sections.add(section.name().equals(LOGIC_UNITS) ? new WorldHash.Section(LOGIC_UNITS, logicUnits) : section);
        }
        return WorldHash.of(sections);
    }
}
