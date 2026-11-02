package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Units carrying payloads: vanilla's {@code PayloadComp} (PayloadComp.java) over a unit's {@link PayloadSlot}, and the
 * payload remote calls of {@code InputHandler} (InputHandler.java:552-659). Every method takes the carrier, which must
 * have a slot ({@code unit.payloads != null}, vanilla's {@code instanceof Payloadc}).
 *
 * <p>The calls run their server-side handler, then publish the {@link PayloadEvent} the sync layer sends as the Call
 * of the same name, as a server-called {@code Call} runs locally before it sends. The {@code request…} handlers are the
 * checks a server makes on a client's request, after the action filters, which are the caller's
 * ({@code server.intent}); AI and logic call the {@code picked…} and {@link #payloadDropped} calls directly, as
 * vanilla's do.
 *
 * <p>Random draws are vanilla's: a sound's pitch when a unit is picked up or anything is dropped, the scatter of a
 * dropped unit. Effects draw nothing on a server.
 */
public final class Payloads{
    private Payloads(){
    }

    /** {@code payloadUsed()}: the sum of each payload's size squared. */
    public static float used(Unit unit){
        PayloadSlot slot = unit.payloads;
        float used = 0f;
        for(int i = 0; i < slot.size(); i++){
            float size = slot.get(i).size();
            used += size * size;
        }
        return used;
    }

    /** {@code canPickup(unit)}: room for it, a type that picks up units, and an AI unit of the carrier's team that may be carried. */
    public static boolean canPickup(Unit unit, Unit target){
        UnitDef type = unit.type();
        return type.pickupUnits() && used(unit) + target.hitSize * target.hitSize <= type.payloadCapacity() + 0.001f
            && target.team == unit.team && target.isAI() && target.type().allowedInPayloads();
    }

    /** {@code canPickup(build)}: room for it, and a building of the carrier's team that {@link Building#canPickup()}. */
    public static boolean canPickup(Unit unit, Building build){
        float size = build.block().size() * Footprint.TILE_SIZE;
        return used(unit) + size * size <= unit.type().payloadCapacity() + 0.001f && build.canPickup() && build.team == unit.team;
    }

    /** {@code canPickupPayload(pay)}: room for a payload a block holds; units only for a type that picks them up. */
    public static boolean canPickupPayload(Unit unit, Payload pay){
        return used(unit) + pay.size() * pay.size() <= unit.type().payloadCapacity() + 0.001f
            && (unit.type().pickupUnits() || !(pay instanceof Payload.UnitPayload));
    }

    /** {@code hasPayload()}. */
    public static boolean hasPayload(Unit unit){
        return !unit.payloads.isEmpty();
    }

    /**
     * {@code pickup(unit)}: the target leaves the world but still counts for its team (its removal uncounts it, so it is
     * counted once more first), and rides last. The pickup sound draws its pitch.
     */
    public static void pickup(Unit unit, Unit target){
        World world = unit.world();
        TeamUnits counts = TeamUnits.of(world);
        if(target.isAdded() && counts != null) counts.updateCount(target.team, target.type(), 1);
        target.remove();
        unit.payloads.add(new Payload.UnitPayload(target));
        // Sounds.payloadPickup.at(self(), Mathf.random(0.9f, 1.1f))
        Mathf.random(world.rand(), 0.9f, 1.1f);
    }

    /**
     * {@code pickup(build)}: {@link Building#pickedUp()}, the tile becomes air ({@code tile.remove()}), then
     * {@link Building#afterPickedUp()}, and the building rides last. It keeps its old tile until something updates it as a
     * payload, as in vanilla.
     */
    public static void pickup(Unit unit, Building build, Cause cause){
        build.pickedUp();
        build.world().buildings().remove(build, cause);
        build.afterPickedUp();
        unit.payloads.add(new Payload.BuildPayload(build));
    }

    /** {@code dropLastPayload()}: drops the last payload where the unit is; whether it went. */
    public static boolean dropLastPayload(Unit unit, Cause cause){
        if(unit.payloads.isEmpty()) return false;
        Payload load = unit.payloads.peek();
        if(tryDropPayload(unit, load, cause)){
            unit.payloads.pop();
            return true;
        }
        return false;
    }

    /**
     * {@code tryDropPayload(payload)}: into the same-team building under the unit if it takes it, else a building onto
     * the ground where it fits, a unit onto ground it can stand on.
     */
    public static boolean tryDropPayload(Unit unit, Payload payload, Cause cause){
        Building on = on(unit);
        // drop off payload on an acceptor if possible
        if(on != null && on.team == unit.team && on.acceptPayload(on, payload)){
            on.handlePayload(on, payload);
            dropSound(unit.world());
            return true;
        }
        return switch(payload){
            case Payload.BuildPayload build -> dropBlock(unit, build, cause);
            case Payload.UnitPayload carried -> dropUnit(unit, carried);
        };
    }

    /** {@code canDropPayload()}: whether {@link #dropLastPayload} could drop now, without the scatter's draw. */
    public static boolean canDropPayload(Unit unit){
        if(unit.payloads.isEmpty()) return false;
        Payload payload = unit.payloads.peek();
        Building on = on(unit);
        if(on != null && on.team == unit.team && on.acceptPayload(on, payload)) return true;
        return switch(payload){
            case Payload.BuildPayload build -> {
                Building tile = build.building();
                float offset = Footprint.worldOffset(tile.block().size());
                int tx = toTile(unit.x - offset), ty = toTile(unit.y - offset);
                yield unit.world().tiles().inBounds(tx, ty) && Build.validPlace(unit.world(), tile.block(), tile.team, tx, ty, tile.rotation, false, true);
            }
            // vanilla quirk: reads the scatter offset its last dropUnit left in Tmp.v1, which is (0, 0) here
            case Payload.UnitPayload carried -> {
                Unit u = carried.unit();
                yield u.canPass(toTile(unit.x), toTile(unit.y)) && groundCount(unit, u) <= 1;
            }
        };
    }

    /**
     * {@code dropUnit(payload)}: scattered up to 2 units off the carrier, onto ground it can pass, unless more than one
     * large ground unit stands there. It takes a new entity id, so it syncs as a new unit, joins the world facing the
     * carrier's way, and the drop sound draws its pitch.
     */
    static boolean dropUnit(Unit unit, Payload.UnitPayload payload){
        Unit u = payload.unit();
        World world = unit.world();
        Rand rand = world.rand();
        // add random offset to prevent unit stacking
        float length = Mathf.random(rand, 2f);
        var offset = new Vec2().setToRandomDirection(rand).scl(length);
        // can't drop ground units; allow stacking for small units for now
        if(!u.canPass(toTile(unit.x + offset.x), toTile(unit.y + offset.y)) || groundCount(unit, u) > 1) return false;
        u.set(unit.x + offset.x, unit.y + offset.y);
        u.rotation = unit.rotation;
        // reset the ID to a new value to make sure it's synced
        u.id(world.ids().next());
        // decrement count to prevent double increment
        TeamUnits counts = TeamUnits.of(world);
        if(!u.isAdded() && counts != null) counts.updateCount(u.team, u.type(), -1);
        u.add();
        u.unloaded();
        dropSound(world);
        return true;
    }

    /** {@code Units.count(x, y, u.physicSize(), o -> o.isGrounded() && o.hitSize > 14f)} around the carrier. */
    private static int groundCount(Unit unit, Unit u){
        SpatialIndex index = SpatialIndex.of(unit.world());
        return index == null ? 0 : index.count(unit.x, unit.y, u.physicSize(), o -> o.isGrounded() && o.hitSize > 14f);
    }

    /**
     * {@code dropBlock(payload)}: the building goes on the tile under the carrier, with its own team and rotation, if it
     * may be placed there ({@code Build.validPlace} without the visibility check).
     */
    static boolean dropBlock(Unit unit, Payload.BuildPayload payload, Cause cause){
        Building tile = payload.building();
        World world = unit.world();
        float offset = Footprint.worldOffset(tile.block().size());
        int tx = toTile(unit.x - offset), ty = toTile(unit.y - offset);
        if(world.tiles().inBounds(tx, ty) && Build.validPlace(world, tile.block(), tile.team, tx, ty, tile.rotation, false, true)){
            payload.place(tx, ty, tile.rotation, cause);
            // lastAccessed and the place sound aren't simulated: no draw
            return true;
        }
        return false;
    }

    /** {@code playPayloadDropSound(payload)}: its pitch draws from the random. */
    private static void dropSound(World world){
        Mathf.random(world.rand(), 0.9f, 1.1f);
    }

    /** {@code tileOn().build}. */
    private static Building on(Unit unit){
        int pos = unit.tileOn();
        return pos == -1 ? null : unit.world().buildings().at(pos);
    }

    /** {@code World.toTile(coord)}. */
    private static int toTile(float coord){
        return Math.round(coord / Footprint.TILE_SIZE);
    }

    // update, removal and death (PayloadComp.java:28-61)

    /**
     * {@code update()}: the carried powered buildings form one graph without an updater, solved first; then every
     * payload follows the unit and updates, and the dead ones go.
     */
    static void update(Unit unit){
        PayloadSlot slot = unit.payloads;
        if(slot.power != null) slot.power.clear();
        // update power graph first, resolve everything
        for(int i = 0; i < slot.size(); i++){
            if(slot.get(i) instanceof Payload.BuildPayload pb && pb.building().power != null){
                if(slot.power == null) slot.power = PowerGraph.createWithoutUpdater(unit.world());
                pb.building().power.graph = null;
                slot.power.add(pb.building());
            }
        }
        if(slot.power != null) slot.power.update();
        for(int i = 0; i < slot.size(); i++){
            Payload pay = slot.get(i);
            pay.set(unit.x, unit.y, unit.rotation);
            pay.update(unit, null);
        }
        // remove dead payloads after they explode
        slot.removeDead();
    }

    /** {@code PayloadComp.remove()}: the unit left the world, and so does everything it carried. */
    static void removed(Unit unit){
        PayloadSlot slot = unit.payloads;
        for(int i = 0; i < slot.size(); i++) slot.get(i).remove();
        slot.clear();
    }

    /** {@code PayloadComp.destroy()}: with {@code unitPayloadsExplode}, the payloads are destroyed with the unit. */
    static void destroyed(Unit unit){
        if(!UnitCalls.rules(unit.world()).unitPayloadsExplode) return;
        PayloadSlot slot = unit.payloads;
        for(int i = 0; i < slot.size(); i++) slot.get(i).destroyed();
    }

    // remote calls (InputHandler.java:552-659)

    /**
     * {@code requestUnitPayload(player, target)}'s checks: an AI ground unit the carrier can pick up, within reach. Then
     * {@link #pickedUnitPayload}. Vanilla filters nothing here.
     */
    public static boolean requestUnitPayload(Unit unit, Unit target){
        if(unit.payloads == null || target == null) return false;
        if(target.isAI() && target.isGrounded() && canPickup(unit, target)
            && target.within(unit.x, unit.y, unit.type().hitSize() * 2f + target.type().hitSize() * 2f)){
            pickedUnitPayload(unit, target);
            return true;
        }
        return false;
    }

    /** {@code requestBuildPayload}'s reach: a building's size, a fifth more, and 5 tiles. Checked before the filters. */
    public static boolean inReach(Unit unit, Building build){
        return unit.within(build.x(), build.y(), Footprint.TILE_SIZE * build.block().size() * 1.2f + Footprint.TILE_SIZE * 5f);
    }

    /**
     * The checks {@code requestBuildPayload(player, build)} makes once the filters allowed it: a building the carrier's
     * team may interact with, whose payload it can take, or which it can lift whole. Then {@link #pickedBuildPayload}.
     */
    public static boolean requestBuildPayload(Unit unit, Building build, Cause cause){
        if(unit.payloads == null || build == null || !Teams.canInteract(unit.team, build.team)) return false;
        // pick up block's payload
        Payload current = build.getPayload();
        if(current != null && canPickupPayload(unit, current)){
            pickedBuildPayload(unit, build, false, cause);
            return true;
        }
        // pick up whole building directly
        if(visible(build) && build.canPickup() && canPickup(unit, build)){
            pickedBuildPayload(unit, build, true, cause);
            return true;
        }
        return false;
    }

    /** {@code build.block.buildVisibility != BuildVisibility.hidden}. */
    public static boolean visible(Building build){
        return !build.block().baked() || !"visibility:hidden".equals(build.block().buildVisibility());
    }

    /**
     * {@code requestDropPayload(player, x, y)} once the filters allowed it: the drop point is limited to 4 tiles from
     * the carrier, then {@link #payloadDropped}, which is sent whether or not anything drops.
     */
    public static void requestDropPayload(Unit unit, float x, float y, Cause cause){
        if(unit.payloads == null || unit.payloads.isEmpty()) return;
        // apply margin of error
        var v = new Vec2(x, y).sub(unit.x, unit.y).limit(Footprint.TILE_SIZE * 4f).add(unit.x, unit.y);
        payloadDropped(unit, v.x, v.y, cause);
    }

    /** {@code Call.pickedUnitPayload(unit, target)}: a carrier picks the target up; anything else just removes it. */
    public static void pickedUnitPayload(Unit unit, Unit target){
        if(target == null) return;
        if(unit.payloads != null) pickup(unit, target);
        else target.remove();
        unit.world().publish(new PayloadEvent.PickedUnit(unit.id(), target.id()));
    }

    /**
     * {@code Call.pickedBuildPayload(unit, build, onGround)}: on the ground, the carrier lifts the building whole (or,
     * if it can't, the building just goes); else it takes what the building holds.
     */
    public static void pickedBuildPayload(Unit unit, Building build, boolean onGround, Cause cause){
        if(build == null) return;
        int pos = build.pos();
        World world = unit.world();
        if(unit.payloads != null){
            if(onGround){
                if(visible(build) && build.canPickup() && canPickup(unit, build)){
                    pickup(unit, build, cause);
                }else{
                    // Sounds.payloadPickup.at(unit, Mathf.random(0.9f, 1.1f)), then build.tile.remove()
                    Mathf.random(world.rand(), 0.9f, 1.1f);
                    world.buildings().remove(build, cause);
                }
            }else{
                Payload current = build.getPayload();
                if(current != null && canPickupPayload(unit, current)){
                    Payload taken = build.takePayload();
                    if(taken != null){
                        Mathf.random(world.rand(), 0.9f, 1.1f);
                        unit.payloads.add(taken);
                    }
                }
            }
        }else if(onGround){
            Mathf.random(world.rand(), 0.9f, 1.1f);
            world.buildings().remove(build, cause);
        }
        world.publish(new PayloadEvent.PickedBuild(unit.id(), pos, onGround));
    }

    /**
     * {@code handleUnitPayload(unit, grabber)} (BuildingComp.java:702): a unit entering a block leaves its player and the
     * world, and a new entity id is posted to the next tick, as the server does so the Calls sent now still name its old
     * one. The payload is for the block to take.
     */
    public static Payload.UnitPayload handleUnitPayload(Unit unit){
        if(unit.isPlayer()) unit.player().clearUnit();
        unit.remove();
        TeamUnits counts = TeamUnits.of(unit.world());
        if(counts != null) counts.postNewId(unit);
        return new Payload.UnitPayload(unit);
    }

    /**
     * {@code Call.unitBuildingControlSelect(unit, build)} (InputHandler.java:774): a living unit of the building's team
     * enters it if the building lets it ({@link Building#canControlSelect}, then {@link Building#onControlSelect}). The
     * Call goes out either way.
     */
    public static void unitBuildingControlSelect(Unit unit, Building build){
        int id = unit.id(), blockUnit = unit.blockTile == null ? -1 : unit.blockTile.pos(), pos = build.pos();
        if(!unit.dead && unit.team == build.team && build.canControlSelect(unit)) build.onControlSelect(unit);
        unit.world().publish(new PayloadEvent.ControlSelect(id, blockUnit, pos));
    }

    /** {@code Call.payloadDropped(unit, x, y)}: the carrier drops its last payload as if it stood at (x, y). */
    public static void payloadDropped(Unit unit, float x, float y, Cause cause){
        if(unit.payloads != null){
            float prevx = unit.x, prevy = unit.y;
            unit.set(x, y);
            dropLastPayload(unit, cause);
            unit.set(prevx, prevy);
        }
        unit.world().publish(new PayloadEvent.Dropped(unit.id(), x, y));
    }
}
