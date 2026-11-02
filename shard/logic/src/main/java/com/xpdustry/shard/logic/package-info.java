/**
 * The mlog VM (note 28, option A: the interpreter): vanilla v160.7's {@code LAssembler}, {@code LExecutor} and the
 * logic half of {@code LogicBuild}, with every observable quirk kept.
 *
 * <ul>
 * <li>{@link com.xpdustry.shard.logic.Assembler} parses code into a shared, immutable
 *     {@link com.xpdustry.shard.logic.Program} (cached by code and options in a
 *     {@link com.xpdustry.shard.logic.ProgramCache}); {@link com.xpdustry.shard.logic.Globals} is vanilla's
 *     {@code GlobalVars} table.</li>
 * <li>{@link com.xpdustry.shard.logic.Processor} is one processor's state and its turn: accumulator, links, waits,
 *     saves. {@link com.xpdustry.shard.logic.LogicWorld} holds one world's globals, {@code op rand} generator,
 *     {@link com.xpdustry.shard.logic.LinkIndex} and {@link com.xpdustry.shard.logic.LogicBudget}.</li>
 * <li>{@link com.xpdustry.shard.logic.LogicHost} is what {@code :sim} implements: sensors, control, memory, units and
 *     the world-processor statements ({@link com.xpdustry.shard.logic.HostOp}).</li>
 * <li>{@link com.xpdustry.shard.logic.LogicSave} and {@link com.xpdustry.shard.logic.LogicConfig} are the save body
 *     (version 5) and the config format, for {@code :compat}.</li>
 * </ul>
 *
 * <p>Math is {@link java.lang.StrictMath}, so results don't depend on the CPU; vanilla's {@code Math} equals it when
 * HotSpot's libm intrinsics are off, which is how the oracle tests run. Owner: WP18.
 */
package com.xpdustry.shard.logic;
