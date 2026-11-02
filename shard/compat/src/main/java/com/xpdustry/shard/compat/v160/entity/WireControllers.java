package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * Unit controllers as {@code TypeIO.writeController} sends them, and back as {@code readController} reads them. Only
 * the kind and a {@code CommandAI}'s orders go on the wire:
 * <ul>
 *   <li>a player's unit: {@link Codecs.PlayerControl} with the player's id;</li>
 *   <li>{@link CommandAi}: {@link Codecs.CommandControl} type 9, with its target position, attack target, command,
 *   queue and stances (ascending ids, as vanilla walks {@code content.unitStances()});</li>
 *   <li>a {@link LogicAi} with a processor: {@link Codecs.LogicControl} with the processor's position;</li>
 *   <li>an assembler drone's AI: {@link Codecs.AssemblerControl};</li>
 *   <li>anything else ({@link NoAi} included): {@link Codecs.OtherControl#GENERIC}.</li>
 * </ul>
 */
public final class WireControllers{
    private WireControllers(){
    }

    /** {@code writeController}'s view of {@code controller}, with building positions in {@code tiles}' wire form. */
    public static Codecs.Controller of(UnitController controller, TileGrid tiles){
        return switch(controller){
            case PlayerControl control -> new Codecs.PlayerControl(control.player.id());
            case LogicAi ai when ai.controller != null -> new Codecs.LogicControl(tiles.toWire(ai.controller.tile()));
            case CommandAi ai -> command(ai, tiles);
            case AiController ai when AiController.ASSEMBLER.equals(ai.kind) -> new Codecs.AssemblerControl();
            default -> Codecs.OtherControl.GENERIC;
        };
    }

    static Codecs.CommandControl command(CommandAi ai, TileGrid tiles){
        Codecs.Vec2 target = ai.targetPos == null ? null : new Codecs.Vec2(ai.targetPos.x, ai.targetPos.y);
        Codecs.Attack attack = switch(ai.attackTarget){
            case null -> null;
            case Building building -> new Codecs.Attack(1, tiles.toWire(building.tile()));
            default -> new Codecs.Attack(0, ai.attackTarget.id());
        };
        var queue = new ArrayList<Codecs.QueueEntry>(ai.queue.length);
        for(CommandAi.QueuedTarget entry : ai.queue){
            queue.add(switch(entry.target){
                case null -> Codecs.QueueEntry.position(entry.x, entry.y);
                case Building building -> Codecs.QueueEntry.building(tiles.toWire(building.tile()));
                case Unit unit -> Codecs.QueueEntry.unit(unit.id());
                // vanilla writes kind 3, with no body, for anything else in the queue
                default -> new Codecs.QueueEntry(3, 0, 0f, 0f);
            });
        }
        var stances = new ArrayList<Integer>();
        for(int stance : ai.stanceIds()) stances.add(stance);
        return new Codecs.CommandControl(Codecs.CommandControl.TYPE, target, attack, ai.command, List.copyOf(queue),
            List.copyOf(stances));
    }

    /**
     * {@code readController(read, prev)} on a server, then the controller {@code afterRead} keeps: a
     * {@link Codecs.CommandControl} becomes a {@link CommandAi} with its orders (vanilla's {@code CommandAI} keeps its
     * state), resolving targets in {@code world}; every other kind leaves {@code prev}, the type's controller, since
     * {@code afterRead} replaces any controller that is not a state-keeping AI. Stances are set as read: valid data
     * never holds incompatible ones, which {@code setStance} would clear.
     */
    static UnitController afterRead(Codecs.Controller controller, UnitController prev, World world){
        // readController's LogicAI keeps its state: the processor at the saved position, the control timer full
        if(controller instanceof Codecs.LogicControl logic){
            Building processor = building(world, logic.processor());
            if(prev instanceof LogicAi ai){
                ai.controller = processor;
                return ai;
            }
            return LogicAi.read(processor);
        }
        if(!(controller instanceof Codecs.CommandControl command)) return prev;
        var ai = prev instanceof CommandAi existing ? existing : new CommandAi(world);
        ai.targetPos = command.target() == null ? null : new Vec2(command.target().x(), command.target().y());
        ai.attackTarget = command.attack() == null ? null : command.attack().kind() == 1
            ? building(world, command.attack().ref()) : unit(world, command.attack().ref());
        // readAttackTarget: a unit target is looked up again once the load added every entity (CommandAI.afterRead)
        ai.readAttackTarget = command.attack() != null && command.attack().kind() != 1 ? command.attack().ref() : -1;
        // a negative command, or one this epoch lacks, is the move command (readController)
        int count = world.content().unitCommands().size();
        ai.command = command.command() >= 0 && command.command() < count ? command.command()
            : world.content().unitCommands().indexOf("move");
        var queue = new ArrayList<CommandAi.QueuedTarget>();
        for(Codecs.QueueEntry entry : command.queue()){
            switch(entry.kind()){
                case 0 -> {
                    Building building = building(world, entry.ref());
                    if(building != null) queue.add(new CommandAi.QueuedTarget(0f, 0f, building));
                }
                case 1 -> {
                    Unit unit = unit(world, entry.ref());
                    if(unit != null) queue.add(new CommandAi.QueuedTarget(0f, 0f, unit));
                }
                case 2 -> queue.add(new CommandAi.QueuedTarget(entry.x(), entry.y(), null));
                default -> {}
            }
        }
        ai.queue = queue.toArray(CommandAi.QueuedTarget[]::new);
        // setStance ignores stop (0), which older saves wrote as the default
        ai.stances.clear();
        int stances = world.content().unitStances().size();
        for(int stance : command.stances()) if(stance > 0 && stance < stances) ai.stances.set(stance);
        return ai;
    }

    /** {@code world.build(pos)}: the building covering a wire position, or null. */
    static Building building(World world, int pos){
        TileGrid tiles = world.tiles();
        int x = Point2.x(pos), y = Point2.y(pos);
        return tiles.inBounds(x, y) ? world.buildings().at(tiles.pos(x, y)) : null;
    }

    /** {@code Groups.unit.getByID(id)}. */
    static Unit unit(World world, int id){
        EntityGroups groups = EntityGroups.of(world);
        return groups != null && groups.byId(id) instanceof Unit unit ? unit : null;
    }
}
