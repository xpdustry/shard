package com.xpdustry.shard.logic;

import com.xpdustry.shard.logic.lang.*;

/**
 * The world side of mlog, which {@code :sim} implements once per world (WP67–69). The VM owns control flow, variables,
 * {@code op}, text, links and timing; everything that reads or changes the world comes here. Values the VM hands over
 * are the objects the host itself put into variables (buildings, units), the table's content definitions and teams
 * ({@link Globals}: {@link com.xpdustry.shard.content.ContentDef}, {@link LogicTeam}), strings, mlog enums
 * ({@link LAccess}…) and {@link java.util.List}s ({@code @queries}). The host puts content and teams back as the
 * table's: {@code sensor @type} gives the epoch's definition, {@code sensor @team} gives {@link Globals#team(int)}.
 *
 * <p>The host applies vanilla's permission checks, because they depend on world state: {@code validLink} for
 * {@code control}, {@code readable}/{@code writable} for memory, {@code rules.logicUnitControl} for the unit
 * statements. Slots are the running processor's: read them with {@link Processor#num(int)} and friends, write them with
 * {@link Processor#setnum(int, double)} and friends.
 *
 * <p>Everything runs on the world's thread, inside the processor's building's turn.
 */
public interface LogicHost{
    //region values

    /**
     * {@code PrintI.toString} for a host object: a building's block name, a unit's type name. Return null for an object
     * the host doesn't know; the VM then prints an enum's name, or {@code [object]}. Content and teams are the VM's.
     */
    String toString(Object value);

    //endregion
    //region buildings

    /**
     * {@code SenseI} on a building or unit ({@code Senseable}): write the result into slot {@code to}. Return false if
     * {@code target} can't be sensed, and the VM writes null. Strings, lists, and {@code @dead} of null are the VM's.
     */
    boolean sensor(Processor p, Object target, Object property, int to);

    /** {@code ControlI}, including its {@code validLink} check (a world processor may control anything). */
    void control(Processor p, LAccess type, Object target, int p1, int p2, int p3, int p4);

    /**
     * {@code ReadI} on an {@code LReadable} (a memory cell or bank, a processor: see
     * {@link Processor#externalRead(Processor, int, int)}). Return false if {@code target} is not readable at all, and
     * the VM reads strings and lists itself or writes null; return true after writing {@code output}, null included
     * when {@code readable(exec)} fails.
     */
    boolean read(Processor p, Object target, int position, int output);

    /** {@code WriteI} on an {@code LWritable}; see {@link Processor#externalWrite(Processor, int, int)}. */
    void write(Processor p, Object target, int position, int value);

    /** {@code PrintFlushI}: if {@code target} is a printable building, give it {@link Processor#text()}. The VM clears the text. */
    void printflush(Processor p, Object target);

    /** {@code DrawFlushI}: a headless server keeps no graphics buffer; vanilla's display only counts the flush. */
    default void drawflush(Processor p, Object target){
    }

    //endregion
    //region links (LogicBuild's link table; see Processor.refreshLinks)

    /** {@code world.build(x, y)}: the building covering the tile, or null. */
    Object building(int x, int y);

    /** {@code LogicBuild.validLink(other)} for {@code p}'s building ({@code other} is not null). */
    boolean validLink(Processor p, Object other);

    /** The building's block, compared by identity to see a link's block change. */
    Object block(Object building);

    /** The building's block name ({@code memory-cell}), from which link names are made. */
    String blockName(Object building);

    /** The building's entity id, which duplicate-link checks compare. */
    int buildingId(Object building);

    /** The building's origin tile, packed as {@code Point2.pack(x, y)}. */
    int buildingTile(Object building);

    //endregion
    //region units, world processors

    /**
     * Every {@link HostOp}: units ({@code radar}, {@code ubind}, {@code ucontrol}, {@code ulocate}, {@code uradar}) and
     * the world processors' statements. It costs one instruction whatever happens; the default does nothing, which is
     * what a headless server does for the client-side statements ({@code cutscene}, {@code playsound}…).
     */
    default void call(Processor p, HostOp op){
    }

    /**
     * {@code Call.syncVariable(build, variable, value)}: send exported variable {@code variable}'s value to clients. The
     * VM has applied vanilla's per-variable rate limit (see {@link Processor}).
     */
    default void sync(Processor p, int variable, Object value){
    }

    /**
     * A variable's value from a save, when {@link Processor#checkReadCode()} applies it: vanilla's {@code Boxed}
     * buildings and units are looked up then ({@code readObjectBoxed(read, true)}). The default keeps it.
     */
    default Object unbox(Object value){
        return value;
    }

    /** {@code state.mapLocales.getProperty(name)}, for {@code localeprint}; null prints as {@code null}. */
    default String localeString(String name){
        return null;
    }

    //endregion
}
