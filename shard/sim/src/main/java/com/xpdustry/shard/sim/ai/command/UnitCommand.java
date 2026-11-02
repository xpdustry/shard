package com.xpdustry.shard.sim.ai.command;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code UnitCommand} (UnitCommand.java:76-116): what an RTS-controlled unit does. The commands are code in
 * vanilla, not content, so their flags are here; their ids are the content's ({@link ContentEpoch#unitCommands()}),
 * which is what saves, syncs and Calls carry. {@link #byId} turns an id into a command, {@link #id} back.
 *
 * <p>Flags, as vanilla's fields:
 * <ul>
 *   <li>{@link #switchToMove}: a position order switches the unit to {@code move} first;</li>
 *   <li>{@link #resetTarget}: switching to or from it drops the move and attack targets;</li>
 *   <li>{@link #snapToBuilding}: a position on an own building becomes the building's centre;</li>
 *   <li>{@link #exactArrival}: the last point must be reached within 1 world unit;</li>
 *   <li>{@link #controller}: the AI kind ({@code AiKinds}) the command runs, or null for {@code CommandAI}'s own
 *   behaviour;</li>
 *   <li>{@link #holdPosition}: {@code extraStances} has {@code holdposition}.</li>
 * </ul>
 */
public enum UnitCommand{
    MOVE("move", null, true, false, false, false),
    REPAIR("repair", "repair-ai", true, true, false, false),
    REBUILD("rebuild", Controllers.BUILDER_AI, true, true, false, false),
    ASSIST("assist", Controllers.ASSIST_AI, true, true, false, false),
    MINE("mine", "miner-ai", true, true, false, false),
    ENTER_PAYLOAD("enterPayload", null, false, false, true, false),
    LOAD_UNITS("loadUnits", null, false, false, false, false),
    LOAD_BLOCKS("loadBlocks", null, false, false, false, true),
    UNLOAD_PAYLOAD("unloadPayload", null, false, false, false, false),
    LOOP_PAYLOAD("loopPayload", null, false, false, false, false);

    private static final UnitCommand[] ALL = values();

    /** The content name, as {@code UnitCommand.name}. */
    public final String contentName;
    /** The AI kind of {@code controller}, or null for the default behaviour. */
    public final String controller;
    public final boolean switchToMove, resetTarget, snapToBuilding, exactArrival;

    UnitCommand(String contentName, String controller, boolean switchToMove, boolean resetTarget, boolean snapToBuilding, boolean exactArrival){
        this.contentName = contentName;
        this.controller = controller;
        this.switchToMove = switchToMove;
        this.resetTarget = resetTarget;
        this.snapToBuilding = snapToBuilding;
        this.exactArrival = exactArrival;
    }

    /** {@code extraStances.contains(holdPosition)}: the builder and repair commands (UnitStance.java:110). */
    public boolean holdPosition(){
        return this == REPAIR || this == ASSIST || this == REBUILD;
    }

    /** {@code controller.get(unit)}: a new AI for the command, or null when it runs {@code CommandAI}'s own behaviour. */
    public AiController controller(World world){
        return controller == null ? null : AiKinds.make(world, controller);
    }

    /** This command's id in {@code content}, or -1 if the content has no such command. */
    public int id(ContentEpoch content){
        return content.unitCommands().indexOf(contentName);
    }

    /** The command called {@code name}, or null. */
    public static UnitCommand of(String name){
        if(name == null) return null;
        for(UnitCommand command : ALL) if(command.contentName.equals(name)) return command;
        return null;
    }

    /** {@code content.unitCommand(id)}: the command with this id in {@code content}, or null for an id no command has. */
    public static UnitCommand byId(ContentEpoch content, int id){
        List<String> names = content.unitCommands();
        return id < 0 || id >= names.size() ? null : of(names.get(id));
    }

    /** {@code allowCommand(unit, command)}: the unit's type lists the command. */
    public boolean allowedFor(Unit unit){
        return unit.params().commands().contains(contentName);
    }
}
