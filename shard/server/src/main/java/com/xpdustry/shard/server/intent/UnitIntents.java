package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.sim.ai.command.*;
import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;

/**
 * The remote calls that bind a player to a unit and order units around (InputHandler.java:310-476, 754-886). Each
 * forwards its packet to everyone once admitted, as vanilla's generated handler does, unless vanilla's method threw
 * ({@code ValidateException}): a denial is not forwarded, and since none of these runs on the client before the server
 * answers, there is nothing else to roll back.
 *
 * <p>The RTS orders ({@code commandUnits}, {@code setUnitCommand}, {@code setUnitStance}) run {@link Orders}, the
 * port of vanilla's handler bodies, after the {@code commandUnits} action filter (W4.2, WP72).
 */
public final class UnitIntents{
    private UnitIntents(){
    }

    /**
     * {@code unitControl(player, unit)}: with possession allowed, take over an AI unit of the player's team that players
     * may control, or let go with none. An unknown unit id reads as none, so it lets go too (vanilla's quirk). A block
     * unit (kind 1) is a turret's, by its position ({@code BlockUnits.at}); like vanilla, the server doesn't check the
     * turret's {@code canControl()}, which the client does.
     */
    public record UnitControl(Policy policy, Codecs.UnitRef unit) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            Unit target = acting.unit(unit);
            if(!world.buildings().rules().possessionAllowed) return Verdict.rejected("possession is off");
            Verdict denied = acting.denied(acting.action(ActionType.CONTROL).unit(target).build());
            if(denied != null) return denied;
            if(target == null){
                player.clearUnit();
            }else if(target.isAI() && target.team == player.team && !target.dead && target.params().playerControllable()){
                Unit before = player.unit();
                player.unit(target);
                if(before != null){
                    if(before.spawnedByCore){
                        target.dockedType = before.type().id();
                    }else if(before.dockedType != -1 && world.content().unit(before.dockedType).coreUnitDock()){
                        // direct dock transfer
                        target.dockedType = before.dockedType;
                    }
                    if(before.spawnedByCore && !before.isPlayer()) before.despawn();
                }
            }else{
                return Verdict.rejected("invalid unit");
            }
            acting.forward(new Packets.UnitControl(player.id(), unit));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code unitClear(player)}: back to the core. A unit that docks returns the player to its docked core unit in
     * place; otherwise the player lets go and respawns at once. The type a unit without a docked type falls back to
     * is the best core's ({@code bestCore()}, WP23), which the spawn seam doesn't expose yet, so such a unit takes the
     * standard respawn.
     */
    public record UnitClear(Policy policy) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            Verdict denied = acting.denied(acting.action(ActionType.RESPAWN).build());
            if(denied != null) return denied;
            if(!player.dead() && !player.unit().spawnedByCore){
                Unit before = player.unit();
                UnitDef docked = before.dockedType == -1 ? null : world.content().unit(before.dockedType);
                if(docked != null && docked.coreUnitDock()){
                    Unit unit = Unit.create(world, docked, player.team);
                    unit.set(before.x, before.y);
                    // translate backwards so it doesn't spawn stuck in the unit
                    if(before.isFlying() && unit.params().flying()){
                        var back = new Vec2().trns(before.rotation + 180f, before.hitSize / 2f + unit.hitSize / 2f);
                        unit.x += back.x;
                        unit.y += back.y;
                    }
                    unit.rotation = before.rotation;
                    player.unit(unit);
                    unit.spawnedByCore = true;
                    unit.add();
                    acting.forward(new Packets.UnitClear(player.id()));
                    return Verdict.ADMITTED;
                }
            }
            player.clearUnit();
            // checkSpawn(): the best core spawns the player's unit
            PlayerSpawns spawns = PlayerSpawns.of(world);
            if(spawns.hasCore(player)) spawns.requestSpawn(player);
            player.deathTimer = Player.DEATH_DELAY + 1f;
            acting.forward(new Packets.UnitClear(player.id()));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code commandUnits(player, ids, buildTarget, unitTarget, posTarget, queue, finalBatch)}: the player's
     * {@code CommandAI} units take the order, switching to {@code move} first unless their command keeps them. An
     * enemy target they can hit is attacked; otherwise they go to the position.
     */
    public record CommandUnits(Policy policy, int[] unitIds, int buildTarget, Codecs.UnitRef unitTarget, float posX, float posY,
                               boolean queueCommand, boolean finalBatch) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            Verdict denied = acting.denied(acting.action(ActionType.COMMAND_UNITS).units(unitIds, -1).build());
            if(denied != null) return denied;
            Building building = acting.building(buildTarget);
            Unit unitTarget = acting.unit(this.unitTarget);
            Orders.commandUnits(world, player.team, units(acting, unitIds), new Orders.Target(building, unitTarget, posX, posY), queueCommand, finalBatch);
            acting.forward(new Packets.CommandUnits(player.id(), unitIds, buildTarget, this.unitTarget, new Codecs.Vec2(posX, posY), queueCommand, finalBatch));
            return Verdict.ADMITTED;
        }
    }

    /** {@code setUnitCommand(player, ids, command)}: units of the player's team whose type has the command take it. */
    public record SetUnitCommand(Policy policy, int[] unitIds, int command) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            // readCommand: an id no command has reads as null, and the handler returns before the filters
            UnitCommand unitCommand = UnitCommand.byId(world.content(), command);
            if(unitCommand == null) return Verdict.rejected("no command " + command);
            Player player = acting.player;
            Verdict denied = acting.denied(acting.action(ActionType.COMMAND_UNITS).units(unitIds, command).build());
            if(denied != null) return denied;
            Orders.setUnitCommand(world, player.team, units(acting, unitIds), unitCommand);
            acting.forward(new Packets.SetUnitCommand(player.id(), unitIds, command));
            return Verdict.ADMITTED;
        }
    }

    /** {@code setUnitStance(player, ids, stance, enable)}: {@code stop} clears orders; other stances turn on or off. */
    public record SetUnitStance(Policy policy, int[] unitIds, int stance, boolean enable) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            List<String> stances = world.content().unitStances();
            if(stance < 0 || stance >= stances.size()) return Verdict.rejected("no stance " + stance);
            Player player = acting.player;
            Verdict denied = acting.denied(acting.action(ActionType.COMMAND_UNITS).units(unitIds, -1).build());
            if(denied != null) return denied;
            Orders.setUnitStance(world, player.team, units(acting, unitIds), stance, enable);
            acting.forward(new Packets.SetUnitStance(player.id(), unitIds, stance, enable));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code commandBuilding(player, positions, target)}: each of the player's buildings whose block is
     * {@code commandable} (unit factories and reconstructors: their rally point) gets the target ({@code onCommand}).
     */
    public record CommandBuilding(Policy policy, int[] buildings, float targetX, float targetY) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            Verdict denied = acting.denied(acting.action(ActionType.COMMAND_BUILDING).buildings(buildings).build());
            if(denied != null) return denied;
            for(int pos : buildings){
                Building build = acting.building(pos);
                if(build == null || build.team != player.team || !build.block().fields().b("commandable")) continue;
                build.onCommand(new Vec2(targetX, targetY));
            }
            acting.forward(new Packets.CommandBuilding(player.id(), buildings, new Codecs.Vec2(targetX, targetY)));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code buildingControlSelect(player, build)} (InputHandler.java:754): the player's unit enters a building of its
     * team that lets it ({@code canControlSelect}): a core respawns the player there, a payload block takes the unit in.
     * Then {@code Call.unitBuildingControlSelect} ({@link Payloads#unitBuildingControlSelect}), and a core unit left
     * behind despawns. Without possession only the best core may be selected.
     */
    public record BuildingControlSelect(Policy policy, int build) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            Building building = acting.building(build);
            if(building == null || player.dead()) return Verdict.rejected("nothing to select");
            // !possessionAllowed && bestCore() != build: the best core is the spawn seam's (WP23)
            if(!world.buildings().rules().possessionAllowed) return Verdict.rejected("possession is off");
            PlayerAction action = acting.action(ActionType.BUILD_SELECT).tile(building.tileX(), building.tileY()).build();
            Verdict denied = acting.denied(action);
            if(denied != null) return denied;
            Unit before = player.unit();
            if(player.team == building.team && building.canControlSelect(before)){
                Payloads.unitBuildingControlSelect(before, building);
                if(!before.dead && before.spawnedByCore && !before.isPlayer()) before.despawn();
            }
            return Verdict.ADMITTED;
        }
    }

    /** {@code Groups.unit.getByID(id)} for each id, in order; null where none is. */
    static Unit[] units(Acting acting, int[] ids){
        var out = new Unit[ids.length];
        for(int i = 0; i < ids.length; i++) out[i] = acting.unit(ids[i]);
        return out;
    }
}
