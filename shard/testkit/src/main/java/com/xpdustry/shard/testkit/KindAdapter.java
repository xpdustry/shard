package com.xpdustry.shard.testkit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * How {@link ShardRunner} reads and configures one kind: the small per-kind piece a family owner writes when the
 * kind's Java fields don't already carry vanilla's names, or when the kind takes configs. Register it from test
 * sources, so it never ships:
 * <pre>{@code
 * @Register
 * public final class ConveyorAdapter implements KindAdapter<Conveyor>{
 *     public KindKey kind(){ return KindKey.of("conveyor"); }
 *
 *     public void fields(Conveyor c, Fields out){
 *         out.put("len", c.len).put("ids", out.names().itemNames(c.ids)).put("xs", c.xs).put("ys", c.ys);
 *     }
 * }
 * }</pre>
 * A kind without an adapter projects its public fields by their Java names ({@link Fields#reflect}) and refuses
 * configs. {@link ShardRunner#adapter} overrides a registered adapter for one runner.
 */
@Registry
public interface KindAdapter<B extends Building>{
    /** The kind this adapter reads: a {@link KindRegistry#key(BlockDef)}. */
    KindKey kind();

    /** Writes the kind's fields under vanilla's names. The default projects every public field of the kind class. */
    default void fields(B building, Fields out){
        out.reflect(building);
    }

    /** Applies a scenario config as vanilla's config handlers for this kind would. */
    default void configure(B building, Config config, ShardNames names){
        throw new UnsupportedOperationException("kind " + kind() + " has no config support in its KindAdapter (" + getClass().getName() + ")");
    }
}
