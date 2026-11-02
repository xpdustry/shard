package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code MinerAI} (MinerAI.java), for mono and for the {@code mine} command: mines the item its core holds
 * least of, from the floor or wall ore nearest the core ({@link OreIndex}), until its stack is full, then flies back,
 * circling the core, and puts the stack in once in range ({@code transferItemTo}).
 *
 * <p>The item is the one with the fewest in the core among those the map has as an ore the type mines and can mine:
 * the type's {@code mineItems}, or, for a command AI without {@code mineauto}, every item it has a stance for. Vanilla
 * quirk: {@code Seq.min} keeps the last of equals ({@code val <= min}), so with an empty core a mono mines lead, not
 * copper.
 */
public class MinerAi extends AiController{
    public static final String KIND = "miner-ai";

    /** {@code mining}: false while it brings its stack back. */
    public boolean mining = true;
    /** {@code targetItem}: the item it mines, or -1. */
    public int targetItem = -1;
    /** {@code ore}: the tile it mines from, as {@code Point2.pack}, or -1. */
    public int ore = -1;

    public MinerAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public MinerAi(){
    }

    protected MinerAi(MinerAi source, CopyContext context){
        super(source, context);
        mining = source.mining;
        targetItem = source.targetItem;
        ore = source.ore;
    }

    /** {@code stanceChanged()}: a command AI that no longer has a stance for the item stops mining it. */
    @Override
    public void stanceChanged(){
        if(targetItem != -1 && unit.controller instanceof CommandAi ai && !ai.hasStance(stance("mineauto")) && !ai.hasStance(itemStance(targetItem))){
            mining = false;
            targetItem = -1;
        }
    }

    @Override
    public void updateMovement(){
        World world = unit.world();
        Building core = Miner.closestCore(unit);

        if(!Miner.canMine(unit) || core == null) return;

        if(!Miner.validMine(unit, unit.mineTile)) unit.mineTile = -1;

        CommandAi ai = unit.controller instanceof CommandAi a ? a : null;
        MineType type = Miner.type(unit);
        OreIndex ores = OreIndex.of(world);

        if(mining){
            if(timerGet(TIMER_TARGET2, 60 * 4) || targetItem == -1){
                if(ai != null && !ai.hasStance(stance("mineauto"))){
                    targetItem = -1;
                    float min = Float.MAX_VALUE;
                    for(int item = 0; item < world.content().items(); item++){
                        if(!minable(type, ores, item) || !ai.hasStance(itemStance(item))) continue;
                        float val = core.items.get(item);
                        if(val <= min){
                            targetItem = item;
                            min = val;
                        }
                    }
                }else{
                    targetItem = -1;
                    float min = Float.MAX_VALUE;
                    for(int item : type.items){
                        if(!minable(type, ores, item)) continue;
                        float val = core.items.get(item);
                        if(val <= min){
                            targetItem = item;
                            min = val;
                        }
                    }
                }
            }

            // core full of the target item, do nothing
            if(targetItem != -1 && core.acceptStack(targetItem, 1, unit.team) == 0){
                unit.clearItem();
                unit.mineTile = -1;
                return;
            }

            // if the inventory is full, drop it off
            if(unit.stackAmount >= unit.itemCapacity() || (targetItem != -1 && !unit.acceptsItem(targetItem))){
                mining = false;
            }else{
                if(timerGet(TIMER_TARGET3, 60) && targetItem != -1){
                    ore = -1;
                    if(type.floor) ore = packed(ores.findClosestOre(core.x(), core.y(), targetItem));
                    if(ore == -1 && type.walls) ore = packed(ores.findClosestWallOre(core.x(), core.y(), targetItem));
                }

                if(ore != -1){
                    float ox = Point2.x(ore) * TILESIZE, oy = Point2.y(ore) * TILESIZE;
                    moveTo(ox, oy, type.range / 2f, 20f);

                    if(unit.within(ox, oy, type.range) && Miner.validMine(unit, ore)){
                        unit.mineTile = ore;
                    }
                }
            }
        }else{
            unit.mineTile = -1;

            if(unit.stackAmount == 0){
                mining = true;
                return;
            }

            float range = type().typeRange;
            if(unit.within(core.x(), core.y(), range)){
                if(core.acceptStack(unit.item(), unit.stackAmount, unit.team) > 0){
                    ItemTransfers.transferItemTo(world, unit, unit.item(), unit.stackAmount, unit.x, unit.y, core);
                }

                unit.clearItem();
                mining = true;
            }

            circle(core.x(), core.y(), range / 1.8f);
        }

        if(!unit.params().flying()){
            unit.updateBoosting(type().boostWhenMining || dangerousFloor(unit));
        }
    }

    /** The item is on the map as an ore the type mines, and the type's tier reaches it. */
    private static boolean minable(MineType type, OreIndex ores, int item){
        return ((type.floor && ores.hasOre(item)) || (type.walls && ores.hasWallOre(item))) && type.canMine(item);
    }

    private int stance(String name){
        return unit.world().content().unitStances().indexOf(name);
    }

    /** {@code ItemUnitStance.getByItem(item)}: the stance named after the item, or -1. */
    private int itemStance(int item){
        return stance("item-" + unit.world().content().item(item).name());
    }

    private int packed(int pos){
        if(pos == -1) return -1;
        var tiles = unit.world().tiles();
        return Point2.pack(tiles.x(pos), tiles.y(pos));
    }

    /**
     * The floor part of a ground support AI's boost: {@code floorOn().isDuct || damageTaken > 0 || isDeep()}. A floor
     * is never a duct; standing on a block, a unit is on air.
     */
    static boolean dangerousFloor(Unit unit){
        BlockDef floor = unit.world().content().block(unit.floorOn());
        return floor instanceof FloorDef f && (f.damageTaken() > 0f || f.isDeep());
    }

    @Override
    public UnitController copy(CopyContext context){
        return new MinerAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.bool(mining).i(targetItem).i(ore);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.bool("mining", mining).i("targetItem", targetItem).i("ore", ore);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        mining = in.bool("mining");
        targetItem = in.i("targetItem");
        ore = in.i("ore");
    }
}
