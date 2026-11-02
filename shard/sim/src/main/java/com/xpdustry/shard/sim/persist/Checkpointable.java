package com.xpdustry.shard.sim.persist;

/**
 * State that stores itself in checkpoints: a building kind, a world part, a controller, any owned object with
 * fields. The class writes and reads its own fields, by name, in a fixed order:
 *
 * <pre>{@code
 * @Kind("router")
 * public final class Router extends Building implements Checkpointable{
 *     public Building lastInput;
 *     public float time;
 *
 *     @Override
 *     public void write(CheckpointOut out){
 *         out.ref("lastInput", lastInput).f("time", time);
 *     }
 *
 *     @Override
 *     public void read(CheckpointIn in){
 *         lastInput = in.ref("lastInput", Building.class);
 *         time = in.f("time");
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Names are the Java field names: the coverage test matches them against the class's fields, and fails when a
 *   field is neither written nor marked {@link com.xpdustry.shard.sim.world.Derived}.</li>
 *   <li>For an entity, write only the class's own fields: the {@code Entity}, {@code Building} and {@code Unit} bases
 *   are stored by {@code sim.persist} first. A subclass of another checkpointable class calls {@code super}
 *   first.</li>
 *   <li>Write every field every time, nulls included: no conditional fields.</li>
 *   <li>References to other world objects go through {@link CheckpointOut#ref}; owned objects through
 *   {@link CheckpointOut#obj}.</li>
 *   <li>A restored object needs a public no-argument constructor (kinds have one already).</li>
 * </ul>
 *
 * <p>Classes that can't implement this (another owner's, a test's) register a {@link ClassCodec} instead.
 */
public interface Checkpointable{
    void write(CheckpointOut out);

    void read(CheckpointIn in);

    /**
     * Called once the whole world is restored and its derived parts are rebuilt, to rebuild this object's
     * {@link com.xpdustry.shard.sim.world.Derived} fields. Other objects may not have run theirs yet.
     */
    default void restored(){
    }
}
