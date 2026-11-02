package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.BuildPlan;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code PrebuildAI} (PrebuildAI.java), which a {@code BuilderAI} hands over to on an AI team with the
 * {@code prebuildAi} rule ({@link BuilderAi#fallback}). Vanilla marks it "not functional"; this is its behaviour as it
 * stands. Every two ticks, while it has no plan, it takes the nearest of its team's plans, weighted toward production,
 * distribution, liquid and crafting blocks: one its core can pay for, or whose items the map all has as floor ores,
 * and that is a production block or touches a building of its team ({@link #findNextPlan}). When the core can't pay,
 * it mines the first missing item ({@link #doMining}) and brings stacks back until it can, not building meanwhile; then
 * it flies to the plan and builds it, dropping a plan that is no longer valid.
 *
 * <p>Vanilla quirks, kept: {@code Seq.min} keeps the last of equals; the plan search and the ore search share
 * {@code timerTarget3}; a queued plan stays in the team's list, so every builder may take the same one;
 * {@code PrebuildAI.sortPlans}, which
 * the indexer runs on {@code WorldLoadEvent}, runs before a load reads the team's plans, so it never reorders them and
 * isn't ported.
 *
 * <p>{@link #lastPlan} is one of the team's plans, by identity, kept through forks and checkpoints by its place in the
 * team's list, as {@link BuilderAi#lastPlan} is.
 */
public class PrebuildAi extends AiController{
    public static final String KIND = "prebuild-ai";

    /** {@code lastPlan}: the team plan it queued last, or null. */
    public BlockPlan lastPlan;
    /** {@code collectBlock}: the block it collects items for, or -1. */
    public int collectBlock = -1;
    public boolean collectingItems, mining;
    /** {@code lastTargetItem}: the item it mined last, or -1. */
    public int lastTargetItem = -1;
    /** {@code ore}: the tile it mines from, as {@code Point2.pack}, or -1. */
    public int ore = -1;
    /** {@link #lastPlan}'s place in the team's plans, read back until {@link #restored()} takes the plan there. */
    private int lastPlanLink = -1;

    public PrebuildAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public PrebuildAi(){
    }

    protected PrebuildAi(PrebuildAi source, CopyContext context){
        super(source, context);
        int link = source.lastPlanIndex();
        List<BlockPlan> plans = link == -1 ? null : teamPlans(context.world(), source.unit.team);
        lastPlan = plans != null && link < plans.size() ? plans.get(link) : detached(source.lastPlan);
        collectBlock = source.collectBlock;
        collectingItems = source.collectingItems;
        mining = source.mining;
        lastTargetItem = source.lastTargetItem;
        ore = source.ore;
    }

    /** {@code priorities[block.category.ordinal()]}: production, distribution, liquid and crafting first, the rest 0. */
    static float priority(BlockDef block){
        return switch(category(block)){
            case "production" -> 11f;
            case "distribution" -> 10f;
            case "liquid" -> 9f;
            case "crafting" -> 8f;
            default -> 0f;
        };
    }

    private boolean canBuild(Building core, int block){
        World world = unit.world();
        Rules rules = UnitCalls.rules(world);
        return rules.infiniteResources || rules.teams.get(unit.team).infiniteResources || hasAll(core.items, place(block), rules.buildCostMultiplier);
    }

    /** {@code core.items.has(requirements, multiplier)}. */
    private static boolean hasAll(ItemModule items, PlaceInfo block, float multiplier){
        for(int i = 0; i < block.items.length; i++){
            if(block.items[i] >= items.length() || !items.has(block.items[i], Math.round(block.amounts[i] * multiplier))) return false;
        }
        return true;
    }

    private BlockPlan findNextPlan(){
        World world = unit.world();
        Teams teams = Teams.of(world);
        Building core = teams == null ? null : teams.core(unit.team);
        BuildingTree tree = world.buildings().tree(unit.team);
        if(tree == null || core == null) return null;
        List<BlockPlan> plans = teams.get(unit.team).plans();
        OreIndex ores = OreIndex.of(world);

        BlockPlan min = null;
        float minValue = Float.MAX_VALUE;
        for(int i = 0; i < plans.size(); i++){
            BlockPlan plan = plans.get(i);
            BlockDef block = world.content().block(plan.block);
            PlaceInfo info = place(plan.block);
            float size = info.size * TILESIZE + 1f;
            float px = plan.x * TILESIZE + info.offset - size / 2f, py = plan.y * TILESIZE + info.offset - size / 2f;
            boolean pass = (canBuild(core, plan.block) || allOres(ores, info))
                && ("production".equals(category(block)) || tree.any(px, py, size, size));
            if(!pass) continue;
            float value = unit.dst(plan.x * TILESIZE, plan.y * TILESIZE) - priority(block) * 200f;
            // Seq.min: the last of equals
            if(value <= minValue){
                min = plan;
                minValue = value;
            }
        }

        return min != null ? min : plans.getFirst();
    }

    /** {@code !Structs.contains(requirements, it -> !indexer.hasOre(it.item))}. */
    private static boolean allOres(OreIndex ores, PlaceInfo info){
        for(int item : info.items) if(ores == null || !ores.hasOre(item)) return false;
        return true;
    }

    private static String category(BlockDef block){
        return block.baked() ? block.fields().s("category") : "distribution";
    }

    @Override
    public void updateMovement(){
        World world = unit.world();
        AiType type = type();

        if(target != null && shouldShoot()){
            unit.lookAt(Targets.x(target), Targets.y(target));
        }else if(!unit.params().flying()){
            unit.lookAt(prefRotation(unit));
        }

        unit.updateBuilding = !collectingItems;

        boolean moving = false;

        if(collectingItems){
            doMining();
        }else if(Builder.buildPlan(unit) != null){
            // approach plan if building
            BuildPlan req = Builder.buildPlan(unit);

            TileGrid tiles = world.tiles();
            int pos = tiles.inBounds(req.x, req.y) ? tiles.pos(req.x, req.y) : -1;
            boolean valid = !(lastPlan != null && lastPlan.removed)
                && ((pos != -1 && world.buildings().at(pos) instanceof ConstructBuild cons && cons.current == req.block)
                || (req.breaking ? Build.validBreak(world, unit.team, req.x, req.y)
                : Build.validPlace(world, world.content().block(req.block), unit.team, req.x, req.y, req.rotation)));

            if(valid){
                float range = Math.min(type.buildRange - 20f, 100f);
                float tx = tileX(world, req.x, req.y), ty = tileY(world, req.x, req.y);
                // move toward the plan
                moveTo(tx, ty, range - 10f, 20f);
                moving = !unit.within(tx, ty, range);
            }else{
                // discard invalid plan
                unit.plans.removeFirst();
                lastPlan = null;
            }
        }else{
            // find new plan
            Teams teams = Teams.of(world);
            if(teams != null && !teams.get(unit.team).plans().isEmpty() && timerGet(TIMER_TARGET3, 2f)){
                BlockPlan plan = findNextPlan();

                if(plan != null && Build.validPlace(world, world.content().block(plan.block), unit.team, plan.x, plan.y, plan.rotation)){
                    if(!canBuild(teams.core(unit.team), plan.block)){
                        collectingItems = true;
                        collectBlock = plan.block;
                        lastTargetItem = -1;
                        ore = -1;
                        timerReset(world, TIMER_TARGET, 0f);
                    }
                    lastPlan = plan;
                    Builder.addBuild(unit, BuildPlan.place(plan.x, plan.y, plan.rotation, world.content().block(plan.block), plan.config), true);
                }
            }
        }

        if(!unit.params().flying()){
            unit.updateBoosting(type.boostWhenBuilding || moving || MinerAi.dangerousFloor(unit));
        }
    }

    /** {@code doMining()}: mine the first item the core lacks for {@link #collectBlock}, and bring it back. */
    void doMining(){
        World world = unit.world();
        Building core = Miner.closestCore(unit);

        if(!Miner.canMine(unit) || core == null || collectBlock == -1) return;

        if(!Miner.validMine(unit, unit.mineTile)) unit.mineTile = -1;

        MineType mine = Miner.type(unit);
        Rules rules = UnitCalls.rules(world);

        if(mining){
            PlaceInfo info = place(collectBlock);
            int targetItem = -1;
            for(int i = 0; i < info.items.length; i++){
                if(!core.items.has(info.items[i], Mathf.ceil(rules.buildCostMultiplier * info.amounts[i]))){
                    targetItem = info.items[i];
                    break;
                }
            }

            if(targetItem != -1){
                lastTargetItem = targetItem;
            }else{
                targetItem = lastTargetItem;
                // hacky way to check if the unit just deposited something
                if(!unit.hasItem() && canBuild(core, collectBlock)){
                    collectingItems = false;
                    return;
                }
            }

            // core full of the target item, do nothing
            if(targetItem != -1 && core.acceptStack(targetItem, 1, unit.team) == 0){
                unit.clearItem();
                unit.mineTile = -1;
                return;
            }

            // if inventory is full, drop it off
            if(targetItem == -1 || unit.stackAmount >= unit.itemCapacity() || !unit.acceptsItem(targetItem)){
                mining = false;
            }else{
                if(timerGet(TIMER_TARGET3, 60)){
                    OreIndex ores = OreIndex.of(world);
                    ore = -1;
                    if(mine.floor) ore = packed(ores.findClosestOre(unit.x, unit.y, targetItem));
                    if(ore == -1 && mine.walls) ore = packed(ores.findClosestWallOre(unit.x, unit.y, targetItem));
                }

                if(ore != -1){
                    float ox = Point2.x(ore) * TILESIZE, oy = Point2.y(ore) * TILESIZE;
                    moveTo(ox, oy, mine.range / 2f, 20f);

                    if(unit.within(ox, oy, mine.range) && Miner.validMine(unit, ore)){
                        unit.mineTile = ore;
                    }
                }
            }
        }else{
            unit.mineTile = -1;

            if(unit.stackAmount == 0){
                mining = true;

                if(canBuild(core, collectBlock)) collectingItems = false;
                return;
            }

            float range = type().typeRange;
            if(unit.within(core.x(), core.y(), range)){
                if(core.acceptStack(unit.item(), unit.stackAmount, unit.team) > 0){
                    ItemTransfers.transferItemTo(world, unit, unit.item(), unit.stackAmount, unit.x, unit.y, core);
                }

                unit.clearItem();
                mining = true;

                if(canBuild(core, collectBlock)) collectingItems = false;
            }

            circle(core.x(), core.y(), range / 1.8f);
        }
    }

    // helpers

    private PlaceInfo place(int block){
        return PlaceInfo.of(unit.world().content().block(block), unit.world().content());
    }

    private int packed(int pos){
        if(pos == -1) return -1;
        TileGrid tiles = unit.world().tiles();
        return Point2.pack(tiles.x(pos), tiles.y(pos));
    }

    /** {@code tile.drawx()}: the tile's position plus the offset of the block on it now. */
    private static float tileX(World world, int x, int y){
        TileGrid tiles = world.tiles();
        return x * TILESIZE + (tiles.inBounds(x, y) ? Footprint.worldOffset(tiles.table().size(tiles.block(tiles.pos(x, y)))) : 0f);
    }

    private static float tileY(World world, int x, int y){
        TileGrid tiles = world.tiles();
        return y * TILESIZE + (tiles.inBounds(x, y) ? Footprint.worldOffset(tiles.table().size(tiles.block(tiles.pos(x, y)))) : 0f);
    }

    private static List<BlockPlan> teamPlans(World world, int team){
        Teams teams = Teams.of(world);
        return teams == null ? null : teams.get(team).plans();
    }

    private int lastPlanIndex(){
        if(lastPlan == null || unit == null) return -1;
        List<BlockPlan> plans = teamPlans(unit.world(), unit.team);
        if(plans == null) return -1;
        for(int i = 0; i < plans.size(); i++) if(plans.get(i) == lastPlan) return i;
        return -1;
    }

    /** A plan no list holds any more: only its {@code removed} flag is read again. */
    private static BlockPlan detached(BlockPlan plan){
        if(plan == null) return null;
        var out = new BlockPlan(plan.x, plan.y, plan.rotation, plan.block, plan.config);
        out.removed = plan.removed;
        return out;
    }

    // state

    @Override
    public UnitController copy(CopyContext context){
        return new PrebuildAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.i(lastPlanIndex()).bool(lastPlan != null && lastPlan.removed).i(collectBlock).bool(collectingItems).bool(mining)
            .i(lastTargetItem).i(ore);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        int link = lastPlanIndex();
        out.i("lastPlanLink", link).obj("lastPlan", link == -1 ? lastPlan : null).i("collectBlock", collectBlock)
            .bool("collectingItems", collectingItems).bool("mining", mining).i("lastTargetItem", lastTargetItem).i("ore", ore);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        lastPlanLink = in.i("lastPlanLink");
        lastPlan = in.obj("lastPlan", BlockPlan.class);
        collectBlock = in.i("collectBlock");
        collectingItems = in.bool("collectingItems");
        mining = in.bool("mining");
        lastTargetItem = in.i("lastTargetItem");
        ore = in.i("ore");
    }

    /** The team's plans are restored now: {@link #lastPlan} is the one at its stored place. */
    @Override
    public void restored(){
        if(lastPlanLink != -1 && unit != null){
            List<BlockPlan> plans = teamPlans(unit.world(), unit.team);
            if(plans != null && lastPlanLink < plans.size()) lastPlan = plans.get(lastPlanLink);
        }
        lastPlanLink = -1;
    }
}
