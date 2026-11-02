package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code RegenProjector.RegenProjectorBuild} (RegenProjector.java): heals every damaged building of its team
 * in a square of {@code range} tiles by {@code healPercent} of its block's health a tick, doubled
 * ({@code optionalMultiplier}) with phase fabric. Projectors don't stack: each queues its heal in the world's
 * {@link RegenMends}, the largest wins, capped at the missing health, and the first projector to update in a frame
 * applies the map. Its targets are refreshed whenever a tile changed ({@link Buildings#tileChanges()}).
 *
 * <p>Vanilla saves none of it. The regen particles' chance and spread draws are made; drawing isn't.
 */
@Kind("regen-projector")
public final class RegenProjector extends Building implements Checkpointable{
    /** {@code targets}, in the team tree's order. */
    public List<Building> targets = new ArrayList<>();
    public int lastChange = -2;
    public float warmup, totalTime, optionalTimer;
    public boolean anyTargets, didRegen;

    @Derived("the block's parameters, read on first use")
    private Params params;

    record Params(int range, float healPercent, float optionalMultiplier, float optionalUseTime, float effectChance){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(14, 12f / 60f, 2f, 60f * 8f, 0.003f);
            var f = block.fields();
            return new Params(f.i("range"), f.f("healPercent"), f.f("optionalMultiplier"), f.f("optionalUseTime"), f.f("effectChance"));
        }
    }

    Params params(){
        if(params == null) params = Params.of(block());
        return params;
    }

    /** {@code updateTargets()}: the team's buildings overlapping the square. */
    public void updateTargets(){
        targets.clear();
        float size = params().range * Footprint.TILE_SIZE;
        // Tmp.r1.setCentered(x, y, range * tilesize)
        TeamBlocks.eachBlock(world(), team, x() - size / 2f, y() - size / 2f, size, size, b -> true, targets::add);
    }

    @Override
    public void updateTile(){
        Params p = params();
        World world = world();
        int changes = world.buildings().tileChanges();
        if(lastChange != changes){
            lastChange = changes;
            updateTargets();
        }
        warmup = Mathf.approachDelta(warmup, didRegen ? 1f : 0f, 1f / 70f);
        totalTime += warmup;
        didRegen = false;
        anyTargets = false;
        // no healing when suppressed
        if(checkSuppression()) return;
        for(Building b : targets){
            if(b.damaged()){
                anyTargets = true;
                break;
            }
        }
        RegenMends mends = RegenMends.of(world);
        if(efficiency > 0){
            if((optionalTimer += edelta() * optionalEfficiency) >= p.optionalUseTime){
                consume();
                optionalTimer = 0f;
            }
            float healAmount = Mathf.lerp(1f, p.optionalMultiplier, optionalEfficiency) * p.healPercent;
            // Math.max prevents stacking
            for(Building build : targets){
                if(!build.damaged() || build.isHealSuppressed()) continue;
                didRegen = true;
                int pos = build.pos();
                float value = mends.get(pos);
                // vanilla's int block health, exact as a float
                float health = build.block().health();
                mends.put(pos, Math.min(Math.max(value, healAmount * edelta() * health / 100f), health - build.health));
                int size = build.block().size();
                if(value <= 0 && Mathf.chanceDelta(world.rand(), p.effectChance * size * size)){
                    // effect.at(build.x + Mathf.range(size * tilesize / 2f - 1f), build.y + Mathf.range(...))
                    Mathf.range(world.rand(), size * Footprint.TILE_SIZE / 2f - 1f);
                    Mathf.range(world.rand(), size * Footprint.TILE_SIZE / 2f - 1f);
                }
            }
        }
        if(mends.lastUpdateFrame != world.ticks()){
            mends.lastUpdateFrame = world.ticks();
            mends.apply(world);
        }
    }

    @Override
    public boolean shouldConsume(){
        return anyTargets;
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("targets", targets.toArray(new Building[0]), targets.size()).i("lastChange", lastChange).f("warmup", warmup)
            .f("totalTime", totalTime).f("optionalTimer", optionalTimer).bool("anyTargets", anyTargets).bool("didRegen", didRegen);
    }

    @Override
    public void read(CheckpointIn in){
        targets = new ArrayList<>(Arrays.asList(in.refs("targets", Building.class)));
        lastChange = in.i("lastChange");
        warmup = in.f("warmup");
        totalTime = in.f("totalTime");
        optionalTimer = in.f("optionalTimer");
        anyTargets = in.bool("anyTargets");
        didRegen = in.bool("didRegen");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (RegenProjector)into;
        out.targets = new ArrayList<>(targets.size());
        for(Building b : targets) out.targets.add(context.map(b));
        out.lastChange = lastChange;
        out.warmup = warmup;
        out.totalTime = totalTime;
        out.optionalTimer = optionalTimer;
        out.anyTargets = anyTargets;
        out.didRegen = didRegen;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(targets.size());
        for(Building b : targets) hasher.i(b.id());
        hasher.i(lastChange).f(warmup).f(totalTime).f(optionalTimer).bool(anyTargets).bool(didRegen);
    }
}
