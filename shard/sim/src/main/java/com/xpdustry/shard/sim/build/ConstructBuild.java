package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code ConstructBlock.ConstructBuild} (ConstructBlock.java:163): a block being built or broken, the
 * {@code build1} to {@code build16} blocks. Builders call {@link #construct} and {@link #deconstruct} with their
 * progress per tick ({@code BuilderComp}); the site takes the block's requirements from the core as it goes, item by
 * item through {@link #accumulator}, and refunds a share of them when broken. At full progress the block replaces the
 * site ({@link Build#constructed}); at none the tile becomes air ({@link Build#deconstructFinish}).
 *
 * <p>The site is solid while the block it builds or the one it replaces is. It never explodes: vanilla's
 * {@code onDestroyed} only plays effects, whatever the replaced building held. A destroyed site that was being built
 * becomes a rebuild plan for that block ({@link RebuildPlans}).
 *
 * <p>Not ported: {@code constructColor}, which only tints the drawing, and {@code lastAccessed} (block logs).
 */
@Kind("construct-block")
public final class ConstructBuild extends Building implements Checkpointable{
    /** {@code current}: the block being built or broken, as a content id; air (0) until set, never -1. */
    public int current;
    /** {@code previous}: the block this replaces, as a content id; air (0) for none. */
    public int previous;
    /** {@code prevBuild}: the buildings that stood here before (detached once replaced), or null. */
    public List<Building> prevBuild;
    public float progress, buildCost;
    /** {@code lastConfig}: the config the last builder carried, as {@link BuildPlan#config} holds it. */
    public Object lastConfig;
    /** {@code lastBuilder}: the last player's unit that worked here, or the finishing unit; may be gone. */
    public Unit lastBuilder;
    public boolean wasConstructing, activeDeconstruct;
    /** Per requirement: items owed, as a fraction, and how much of the requirement the progress covered. Null until set. */
    public float[] accumulator, totalAccumulator;
    /** Per requirement: items still to take from the core (refunded so far, when breaking). */
    public int[] itemsLeft;

    /** {@code checkSolid()}: solid while either block is. */
    @Override
    public boolean checkSolid(){
        var table = world().tiles().table();
        return table.solid(current) || table.solid(previous);
    }

    /** {@code updateTile()}: a site with nothing to build leaves the update list; every tick ends any deconstruction. */
    @Override
    public void updateTile(){
        // vanilla's remove() here only leaves the group: the site stays on its tiles
        if(current == 0) remove();
        activeDeconstruct = false;
    }

    /** {@code onDestroyed()}: effects only, no explosion. */
    @Override
    public void onDestroyed(){
    }

    @Override
    public double sense(LAccess sensor){
        return switch(sensor){
            case progress -> Mathf.clamp(progress);
            case breaking -> activeDeconstruct ? 1 : 0;
            default -> super.sense(sensor);
        };
    }

    @Override
    public Object senseObject(LAccess sensor){
        return sensor == LAccess.building ? world().content().block(current) : super.senseObject(sensor);
    }

    /**
     * {@code construct(builder, core, amount, config)} (ConstructBlock.java:278): progress by {@code amount} of the
     * build, as far as the core's items allow, taking them as the accumulators fill; at full progress, once every
     * item is paid, the block replaces the site.
     */
    public void construct(Unit builder, Core core, float amount, Object config){
        if(accumulator == null || totalAccumulator == null) return;
        Rules rules = rules();
        wasConstructing = true;
        activeDeconstruct = false;
        if(builder.isPlayer()) lastBuilder = builder;
        lastConfig = config;
        PlaceInfo current = info(this.current);
        if(current.items.length != accumulator.length || totalAccumulator.length != current.items.length) setConstruct(previous, this.current);
        boolean teamInfinite = rules.teams.get(team).infiniteResources;
        boolean infinite = teamInfinite || rules.infiniteResources;
        float maxProgress = core == null || teamInfinite ? amount : checkRequired(core.items, amount, false);
        for(int i = 0; i < current.items.length; i++){
            int reqamount = Math.round(rules.buildCostMultiplier * current.amounts[i]);
            accumulator[i] += Math.min(reqamount * maxProgress, reqamount - totalAccumulator[i]);
            totalAccumulator[i] = Math.min(totalAccumulator[i] + reqamount * maxProgress, reqamount);
        }
        maxProgress = core == null || teamInfinite ? maxProgress : checkRequired(core.items, maxProgress, true);
        progress = Mathf.clamp(progress + maxProgress);
        if(progress >= 1f || rules.infiniteResources){
            boolean canFinish = !Build.overPlacementLimit(world(), current, team);
            // leftover items rounding kept back: take them now, or wait
            if(!infinite){
                for(int i = 0; i < itemsLeft.length; i++){
                    if(itemsLeft[i] > 0){
                        if(core != null && core.items.has(current.items[i], itemsLeft[i])){
                            core.items.remove(current.items[i], itemsLeft[i]);
                            itemsLeft[i] = 0;
                        }else{
                            canFinish = false;
                            break;
                        }
                    }
                }
            }
            if(canFinish){
                if(lastBuilder == null) lastBuilder = builder;
                Build.constructed(world(), tile(), current.block, lastBuilder, rotation, builder.team, config);
            }
        }
    }

    /**
     * {@code deconstruct(builder, core, amount)} (ConstructBlock.java:335): regress by {@code amount}, refunding the
     * rules' share of the requirements into the core as they accumulate; at the block's threshold the rounding's
     * leftovers are refunded too and the tile becomes air.
     */
    public void deconstruct(Unit builder, Core core, float amount){
        if(accumulator == null || totalAccumulator == null) return;
        Rules rules = rules();
        // reset accumulated resources when switching modes
        if(wasConstructing){
            Arrays.fill(accumulator, 0);
            Arrays.fill(totalAccumulator, 0);
        }
        wasConstructing = false;
        activeDeconstruct = true;
        float deconstructMultiplier = rules.deconstructRefundMultiplier;
        if(builder.isPlayer()) lastBuilder = builder;
        PlaceInfo current = info(this.current);
        if(current.items.length != accumulator.length || totalAccumulator.length != current.items.length) setDeconstruct(this.current);
        // you can't deconstruct more than there is
        float clampedAmount = Math.min(amount, progress);
        for(int i = 0; i < current.items.length; i++){
            int reqamount = Math.round(rules.buildCostMultiplier * current.amounts[i]);
            accumulator[i] += Math.min(clampedAmount * deconstructMultiplier * reqamount, deconstructMultiplier * reqamount - totalAccumulator[i]);
            totalAccumulator[i] = Math.min(totalAccumulator[i] + reqamount * clampedAmount * deconstructMultiplier, reqamount);
            int accumulated = (int)accumulator[i];
            if(clampedAmount > 0 && accumulated > 0){
                // unlockedNowHost(): always outside the campaign
                if(core != null){
                    int accepting = Math.min(accumulated, core.storageCapacity - core.items.get(current.items[i]));
                    // transferred directly, as this is not production
                    if(!rules.infiniteResources) core.items.add(current.items[i], accepting);
                    itemsLeft[i] += accepting;
                    accumulator[i] -= accepting;
                }else{
                    accumulator[i] -= accumulated;
                }
            }
        }
        progress = Mathf.clamp(progress - amount);
        if(progress <= current.deconstructThreshold || rules.infiniteResources){
            // refund what rounding kept back
            if(core != null && !rules.infiniteResources){
                for(int i = 0; i < itemsLeft.length; i++){
                    int target = Mathf.round(current.amounts[i] * rules.buildCostMultiplier * rules.deconstructRefundMultiplier);
                    int remaining = target - itemsLeft[i];
                    core.items.add(current.items[i], Mathf.clamp(remaining, 0, core.storageCapacity - core.items.get(current.items[i])));
                    itemsLeft[i] = target;
                }
            }
            if(lastBuilder == null) lastBuilder = builder;
            Build.deconstructFinish(world(), tile(), current.block, lastBuilder);
        }
    }

    /**
     * {@code checkRequired(inventory, amount, remove)}: the progress the core's items allow, each requirement owing
     * its accumulated whole items; with {@code remove}, takes them.
     */
    private float checkRequired(ItemModule inventory, float amount, boolean remove){
        Rules rules = rules();
        float maxProgress = amount;
        boolean infinite = rules.teams.get(team).infiniteResources || rules.infiniteResources;
        PlaceInfo current = info(this.current);
        for(int i = 0; i < current.items.length; i++){
            // no need to remove items already taken out
            if(itemsLeft[i] == 0) continue;
            int item = current.items[i];
            int sclamount = Math.round(rules.buildCostMultiplier * current.amounts[i]);
            int required = (int)accumulator[i];
            if(inventory.get(item) == 0 && sclamount != 0){
                maxProgress = 0f;
            }else if(required > 0){
                int maxUse = Math.min(required, inventory.get(item));
                float fraction = maxUse / (float)required;
                maxProgress = Math.min(maxProgress, maxProgress * fraction);
                accumulator[i] -= maxUse;
                if(remove && !infinite){
                    inventory.remove(item, maxUse);
                    itemsLeft[i] -= maxUse;
                }
            }
        }
        return maxProgress;
    }

    /** {@code progress()}. */
    public float progress(){
        return progress;
    }

    /** {@code setConstruct(previous, block)}: builds {@code block} over {@code previous} from nothing. */
    public void setConstruct(int previous, int block){
        Rules rules = rules();
        PlaceInfo info = info(block);
        wasConstructing = true;
        current = block;
        this.previous = previous;
        buildCost = info.buildTime * rules.buildCostMultiplier;
        itemsLeft = new int[info.items.length];
        accumulator = new float[info.items.length];
        totalAccumulator = new float[info.items.length];
        for(int i = 0; i < info.items.length; i++) itemsLeft[i] = Mathf.round(info.amounts[i] * rules.buildCostMultiplier);
        // pathfinder.updateTile(tile): the grid's solidity follows, the path flush reads the change list
        world().buildings().solidityChanged(this);
    }

    /** {@code setDeconstruct(previous)}: breaks {@code previous} from full progress. */
    public void setDeconstruct(int previous){
        PlaceInfo info = info(previous);
        wasConstructing = false;
        this.previous = previous;
        progress = 1f;
        current = previous;
        buildCost = info.buildTime * rules().buildCostMultiplier;
        itemsLeft = new int[info.items.length];
        accumulator = new float[info.items.length];
        totalAccumulator = new float[info.items.length];
        world().buildings().solidityChanged(this);
    }

    /** The block being built or broken. */
    public BlockDef currentBlock(){
        return world().content().block(current);
    }

    /** The block being replaced. */
    public BlockDef previousBlock(){
        return world().content().block(previous);
    }

    private PlaceInfo info(int block){
        return Construction.of(world()).info(block);
    }

    // state

    /** {@code lastConfig} is stored as {@link BuildPlan}'s tagged bytes, since its type varies. */
    @Override
    public void write(CheckpointOut out){
        var config = new Writes(16);
        BuildPlan.writeConfig(lastConfig, config);
        out.i("current", current).i("previous", previous)
            .refs("prevBuild", prevBuild == null ? null : prevBuild.toArray(Building[]::new), prevBuild == null ? 0 : prevBuild.size())
            .f("progress", progress).f("buildCost", buildCost).bytes("lastConfig", config.buffer(), config.size())
            .ref("lastBuilder", lastBuilder).bool("wasConstructing", wasConstructing).bool("activeDeconstruct", activeDeconstruct)
            .floats("accumulator", accumulator).floats("totalAccumulator", totalAccumulator).ints("itemsLeft", itemsLeft);
    }

    @Override
    public void read(CheckpointIn in){
        current = in.i("current");
        previous = in.i("previous");
        Building[] prev = in.refs("prevBuild", Building.class);
        prevBuild = prev == null ? null : new ArrayList<>(Arrays.asList(prev));
        progress = in.f("progress");
        buildCost = in.f("buildCost");
        var config = new Reads(in.bytes("lastConfig"));
        try{
            lastConfig = BuildPlan.readConfig(config);
        }catch(RuntimeException e){
            throw new CheckpointException("a construct site's config is corrupt", e);
        }
        lastBuilder = in.ref("lastBuilder", Unit.class);
        wasConstructing = in.bool("wasConstructing");
        activeDeconstruct = in.bool("activeDeconstruct");
        accumulator = in.floats("accumulator");
        totalAccumulator = in.floats("totalAccumulator");
        itemsLeft = in.ints("itemsLeft");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ConstructBuild)into;
        out.current = current;
        out.previous = previous;
        if(prevBuild != null){
            out.prevBuild = new ArrayList<>(prevBuild.size());
            for(Building building : prevBuild) out.prevBuild.add(context.map(building));
        }
        out.progress = progress;
        out.buildCost = buildCost;
        out.lastConfig = BuildPlan.copyConfig(lastConfig);
        out.lastBuilder = context.map(lastBuilder);
        out.wasConstructing = wasConstructing;
        out.activeDeconstruct = activeDeconstruct;
        out.accumulator = accumulator == null ? null : accumulator.clone();
        out.totalAccumulator = totalAccumulator == null ? null : totalAccumulator.clone();
        out.itemsLeft = itemsLeft == null ? null : itemsLeft.clone();
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(current).i(previous).i(prevBuild == null ? -1 : prevBuild.size());
        if(prevBuild != null) for(Building building : prevBuild) hasher.i(building.id());
        hasher.f(progress).f(buildCost);
        BuildPlan.hashConfig(lastConfig, hasher);
        hasher.i(lastBuilder == null ? -1 : lastBuilder.id()).bool(wasConstructing).bool(activeDeconstruct);
        hasher.bool(accumulator != null);
        if(accumulator != null){
            hasher.i(accumulator.length);
            for(int i = 0; i < accumulator.length; i++) hasher.f(accumulator[i]).f(totalAccumulator[i]).i(itemsLeft[i]);
        }
    }
}
