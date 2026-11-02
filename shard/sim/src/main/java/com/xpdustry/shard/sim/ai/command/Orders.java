package com.xpdustry.shard.sim.ai.command;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The bodies of vanilla's RTS remote calls (InputHandler.java:310-476, 661-683) once the server admitted them: what
 * {@code commandUnits}, {@code setUnitCommand} and {@code setUnitStance} do to a team's units, and
 * {@code unitEnteredPayload}, which {@link CommandAi}'s {@code enterPayload} calls. The intents ({@code server.intent})
 * decode, filter and forward; units are looked up by the caller ({@code Groups.unit.getByID}: added units only).
 */
public final class Orders{
    private Orders(){
    }

    /** One {@code commandUnits} target: a building, a unit, or a position. */
    public record Target(Building building, Unit unit, float x, float y){
        /** {@code buildTarget == null ? unitTarget : buildTarget}. */
        public Entity entity(){
            return building != null ? building : unit;
        }
    }

    /**
     * {@code commandUnits(player, ids, buildTarget, unitTarget, posTarget, queueCommand, finalBatch)} for a player of
     * {@code team}: each of its command-AI units switches to {@code move} unless its command keeps it, then attacks an
     * enemy target it can hit or goes to the position, now or queued. The final batch forms the units ordered to the
     * same point, in this call and the earlier ones of its batch, into one group per physics layer, unless the order
     * was queued or this call names a single unit.
     *
     * @param units the units the ids name, in order; null for an id that names none
     */
    public static void commandUnits(World world, int team, Unit[] units, Target target, boolean queueCommand, boolean finalBatch){
        Entity teamTarget = target.entity();
        float tx = teamTarget != null ? Targets.x(teamTarget) : target.x, ty = teamTarget != null ? Targets.y(teamTarget) : target.y;
        CommandBatches batches = CommandBatches.of(world);
        CommandBatches.Batch toAdd = batches.get(tx, ty);
        var position = new Vec2(target.x, target.y);

        for(Unit unit : units){
            if(unit == null || unit.team != team || !(unit.controller instanceof CommandAi ai)) continue;
            // implicitly order it to move
            UnitCommand current = ai.command();
            if(current == null || current.switchToMove) ai.command(unit, UnitCommand.MOVE);

            if(teamTarget != null && Targets.team(teamTarget) != team && !(target.unit != null && !canTarget(unit, target.unit))
                && !(target.building != null && !unit.type().targetGround())){
                if(queueCommand){
                    ai.commandQueue(new CommandAi.QueuedTarget(0f, 0f, teamTarget));
                }else{
                    ai.queue = new CommandAi.QueuedTarget[0];
                    ai.commandTarget(teamTarget);
                }
            }else{
                // posTarget != null: TypeIO.readVec2 never reads null
                if(queueCommand){
                    ai.commandQueue(new CommandAi.QueuedTarget(target.x, target.y, null));
                }else{
                    ai.queue = new CommandAi.QueuedTarget[0];
                    ai.commandPosition(position);
                }
            }

            if(ai.queue.length <= 0) ai.group = null;
            toAdd.add(unit);
        }

        // in the final batch, assign formations based on everything that was commanded
        if(!finalBatch) return;
        // each physics layer has its own group
        var groups = new UnitGroup[UnitGroup.LAYERS];
        CommandBatches.Batch batch = batches.remove(tx, ty);
        for(int i = 0; i < batch.size; i++){
            Unit unit = batch.units[i];
            // only assign a group when this is not a queued command
            if(unit.controller instanceof CommandAi ai && ai.queue.length == 0 && units.length > 1){
                int layer = unit.collisionLayer();
                if(layer == -1) layer = 0;
                if(groups[layer] == null) groups[layer] = new UnitGroup();
                groups[layer].add(unit);
                ai.group = groups[layer];
            }
        }
        for(int i = 0; i < groups.length; i++){
            UnitGroup group = groups[i];
            if(group != null && group.size > 0) group.calculateFormation(world, tx, ty, i);
        }
    }

    /** {@code unit.canTarget(other)} (UnitComp.java:517): the unit's weapons reach the other's layer. */
    public static boolean canTarget(Unit unit, Unit other){
        return (other.isGrounded() && unit.type().targetGround()) || (other.isFlying() && unit.type().targetAir());
    }

    /**
     * {@code setUnitCommand(player, ids, command)} for a player of {@code team}: units whose type has the command take
     * it; switching to or from a command that resets targets drops them, and stances the new command doesn't allow
     * are turned off, in id order.
     */
    public static void setUnitCommand(World world, int team, Unit[] units, UnitCommand command){
        UnitStances table = UnitStances.of(world.content());
        var allowed = new IntSeq();
        for(Unit unit : units){
            if(unit == null || unit.team != team || !(unit.controller instanceof CommandAi ai) || !command.allowedFor(unit)) continue;
            boolean reset = command.resetTarget || ai.currentCommand().resetTarget;
            ai.command(unit, command);
            if(reset){
                ai.targetPos = null;
                ai.attackTarget = null;
            }
            // make sure its current stance is valid with its current command
            allowed.clear();
            table.stancesOf(unit, ai.currentCommand(), allowed);
            for(int stance = 0; stance < table.size; stance++){
                // disable stances that the unit does not support anymore
                if(ai.hasStance(stance) && !allowed.contains(stance)) ai.disableStance(stance);
            }
        }
    }

    /**
     * {@code setUnitStance(player, ids, stance, enable)} for a player of {@code team}: {@code stop} clears the units'
     * orders; another stance their type allows turns on, or off for a toggle that is being disabled.
     */
    public static void setUnitStance(World world, int team, Unit[] units, int stance, boolean enable){
        UnitStances table = UnitStances.of(world.content());
        for(Unit unit : units){
            if(unit == null || unit.team != team || !(unit.controller instanceof CommandAi ai)) continue;
            if(stance == table.stop){
                // not a real stance, just cancels orders
                ai.clearCommands();
            }else if(table.allowed(unit, ai.currentCommand(), stance)){
                // if toggle is not allowed, the stance will always be set to true when pressed
                ai.setStance(stance, !table.toggles(stance) || enable);
            }
        }
    }

    /**
     * {@code Call.unitEnteredPayload(unit, build)}: the unit leaves the world, an {@code enterPayload} order is done,
     * and the block takes it as a payload if it still will; if not, the unit is gone all the same (vanilla's quirk).
     */
    public static void unitEnteredPayload(Unit unit, Building build){
        if(unit == null || build == null || unit.team != build.team) return;
        World world = unit.world();
        int id = unit.id(), pos = build.pos();
        unit.remove();
        // reset the enter command
        if(unit.controller instanceof CommandAi ai && ai.command() == UnitCommand.ENTER_PAYLOAD){
            ai.clearCommands();
            ai.command = UnitCommand.MOVE.id(world.content());
        }
        var payload = new Payload.UnitPayload(unit);
        if(build.acceptPayload(build, payload)) build.handlePayload(build, payload);
        world.publish(new PayloadEvent.EnteredPayload(id, pos));
    }
}
