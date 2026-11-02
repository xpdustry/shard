package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Items moving between units and buildings, as vanilla's {@code InputHandler} Calls do on a server
 * (InputHandler.java:160-254): each runs the Call's handler here, then publishes its {@link ItemEvent}, the packet to
 * everyone, as the generated {@code Call} runs a {@code Loc.server} handler before it sends. Players (the item
 * intents), processors ({@code ucontrol itemDrop}/{@code itemTake}), miners and the AIs all move items through these,
 * so each transfer sends its Call once.
 *
 * <p>The item flying ({@code createItemTransfer}, {@code transferItemEffect}) is an effect, Tier E: its runs draw
 * nothing and change nothing on a server, so they aren't kept. The one delayed effect that changes state is
 * {@link #transferItemToUnit}'s, which lands the item when the effect ends.
 */
public final class ItemTransfers{
    /** {@code Fx.itemTransfer.lifetime}: frames an item flies before {@link #transferItemToUnit} lands it. */
    public static final float TRANSFER_TIME = 12f;

    private ItemTransfers(){
    }

    /**
     * {@code Call.transferItemTo(unit, item, amount, x, y, build)}: the unit (or nobody, {@code unit} null) puts
     * {@code amount} of {@code item} into {@code build}; the unit's stack loses it only if it holds that item.
     */
    public static void transferItemTo(World world, Unit unit, int item, int amount, float x, float y, Building build){
        if(build != null && build.items != null){
            if(unit != null && unit.item() == item) unit.stackAmount = Math.max(unit.stackAmount - amount, 0);
            if(amount > 0) build.handleStack(item, amount, unit == null ? -1 : unit.team);
        }
        world.publish(new ItemEvent.TransferItemTo(id(unit), blockUnit(unit), item, amount, x, y, build == null ? -1 : build.tile()));
    }

    /**
     * {@code Call.takeItems(build, item, amount, to)}: the unit takes up to {@code amount} of {@code item}, as much as
     * it has room for, out of {@code build}.
     */
    public static void takeItems(World world, Building build, int item, int amount, Unit to){
        if(to != null && build != null){
            int removed = build.removeStack(item, Math.min(to.maxAccepted(item), amount));
            if(removed != 0) to.addItem(item, removed);
        }
        world.publish(new ItemEvent.TakeItems(build == null ? -1 : build.tile(), item, amount, id(to), blockUnit(to)));
    }

    /**
     * {@code InputHandler.transferItemToUnit(item, x, y, to)}, called directly, not as a Call: the item flies from
     * ({@code x}, {@code y}) and joins the unit's stack {@link #TRANSFER_TIME} frames later, whatever became of the unit
     * meanwhile ("this is clientside, since items are synced anyway", MinerComp.java:107).
     */
    public static void transferItemToUnit(World world, int item, float x, float y, Unit to){
        if(to == null) return;
        world.timers().run(TRANSFER_TIME, new Landed(to, item));
    }

    private static int id(Unit unit){
        return unit == null || unit.blockTile != null ? -1 : unit.id();
    }

    private static int blockUnit(Unit unit){
        return unit == null || unit.blockTile == null ? -1 : unit.blockTile.pos();
    }

    /** The end of {@link #transferItemToUnit}'s effect: {@code to.addItem(item)}. */
    public record Landed(Unit unit, int item) implements Timers.Task{
        @Override
        public void run(World world){
            unit.addItem(item);
        }
    }
}
