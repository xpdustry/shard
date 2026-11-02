package com.xpdustry.shard.sim.building;

import java.util.*;

/**
 * Vanilla's {@code BlockFlag}s, in its order: the tags a block's definition lists in {@code flags}, by which the
 * world's {@link BlockIndex} files its buildings per team. Ordinals are vanilla's, so a flag's set index matches
 * {@code flagMap[team][flag.ordinal()]}.
 */
public enum BlockFlag{
    core, storage, generator, turret, factory, repair, battery, reactor, extinguisher, drill, shield,
    launchPad, unitCargoUnloadPoint, unitAssembler, hasFogRadius, steamVent, blockRepair, synced;

    private static final BlockFlag[] ALL = values();

    /** {@code BlockFlag.allLogic}: the flags mlog's {@code ulocate building} may name. */
    public static final List<BlockFlag> LOGIC = List.of(core, storage, generator, turret, factory, repair, battery, reactor, drill, shield);

    /** The flag named {@code name} as the content names it (vanilla's enum name), or null. */
    public static BlockFlag named(String name){
        for(BlockFlag flag : ALL){
            if(flag.name().equals(name)) return flag;
        }
        return null;
    }

    /** The flag with this ordinal. */
    public static BlockFlag of(int ordinal){
        return ALL[ordinal];
    }

    /** How many flags there are. */
    public static int count(){
        return ALL.length;
    }
}
