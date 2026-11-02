package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * Kinds whose blocks never have a building: vanilla's environment classes (floors, walls, ores, props), whose
 * behaviour is the tile grid's predicates. A block family registers its keys with {@code @Register}.
 * {@link KindRegistry} supports them without a building class, so content that uses them loads, and never
 * instantiates one: they are no {@link Building} subclass, so nothing about them is world state.
 */
@Registry
public interface TileKinds{
    /** The kind keys, in any order. */
    List<KindKey> keys();
}
