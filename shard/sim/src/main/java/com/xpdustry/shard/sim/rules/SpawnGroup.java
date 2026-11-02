package com.xpdustry.shard.sim.rules;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * One wave spawn group (vanilla {@code SpawnGroup}). Content is held by wire id; -1 stands for vanilla's null.
 *
 * <p>{@link #equals} is vanilla's: it ignores {@code spawn}, {@code payloads} and {@code team}, which only matters
 * when the rules JSON decides whether {@code spawns} differs from its default.
 */
public final class SpawnGroup implements Checkpointable{
    /** Vanilla's {@code SpawnGroup.never}. */
    public static final int NEVER = Integer.MAX_VALUE;

    /** The unit type id. */
    public int type = VanillaIds.DAGGER;
    /** The last wave this group spawns in. */
    public int end = NEVER;
    /** The first wave this group spawns in. */
    public int begin;
    /** Spawns every {@code spacing} waves. */
    public int spacing = 1;
    /** The most units this group spawns at once. */
    public int max = 40;
    /** Waves per extra unit. */
    public float unitScaling = NEVER;
    public float shields = 0f;
    public float shieldScaling = 0f;
    public int unitAmount = 1;
    /** The spawn point position, or -1 for all of them. */
    public int spawn = -1;
    /** Unit type ids carried as payload, or null. */
    public List<Integer> payloads;
    /** The status effect id, or -1. */
    public int effect = -1;
    /** Items given to each unit, or null. */
    public ItemStack items;
    /** The team, or -1 for the wave team. */
    public int team = -1;

    public SpawnGroup(){
    }

    public SpawnGroup(int type){
        this.type = type;
    }

    /** {@code canSpawn(position)}: every spawn point, or only {@link #spawn}. */
    public boolean canSpawn(int position){
        return spawn == -1 || spawn == position;
    }

    /**
     * {@code getSpawned(wave)}: units per spawn point on zero-based {@code wave}. Vanilla quirk kept: a spacing of 0 is
     * set to 1 here, in the rules.
     */
    public int getSpawned(int wave){
        if(spacing == 0) spacing = 1;
        if(wave < begin || wave > end || (wave - begin) % spacing != 0) return 0;
        return Math.min(unitAmount + (int)(((wave - begin) / spacing) / unitScaling), max);
    }

    /** {@code getShield(wave)}: each unit's shield on zero-based {@code wave}. */
    public float getShield(int wave){
        return Math.max(shields + shieldScaling * (wave - begin), 0);
    }

    public SpawnGroup copy(){
        var out = new SpawnGroup(type);
        out.end = end;
        out.begin = begin;
        out.spacing = spacing;
        out.max = max;
        out.unitScaling = unitScaling;
        out.shields = shields;
        out.shieldScaling = shieldScaling;
        out.unitAmount = unitAmount;
        out.spawn = spawn;
        out.payloads = payloads == null ? null : new ArrayList<>(payloads);
        out.effect = effect;
        out.items = items;
        out.team = team;
        return out;
    }

    public void hash(Hasher hasher){
        hasher.i(type).i(end).i(begin).i(spacing).i(max).f(unitScaling).f(shields).f(shieldScaling).i(unitAmount)
            .i(spawn).i(effect).i(team);
        hasher.i(payloads == null ? -1 : payloads.size());
        if(payloads != null) for(int unit : payloads) hasher.i(unit);
        hasher.bool(items != null);
        if(items != null) hasher.i(items.item()).i(items.amount());
    }

    @Override
    public void write(CheckpointOut out){
        out.i("type", type).i("end", end).i("begin", begin).i("spacing", spacing).i("max", max).f("unitScaling", unitScaling)
            .f("shields", shields).f("shieldScaling", shieldScaling).i("unitAmount", unitAmount).i("spawn", spawn)
            .ints("payloads", payloads == null ? null : payloads.stream().mapToInt(Integer::intValue).toArray())
            .i("effect", effect).obj("items", items).i("team", team);
    }

    @Override
    public void read(CheckpointIn in){
        type = in.i("type");
        end = in.i("end");
        begin = in.i("begin");
        spacing = in.i("spacing");
        max = in.i("max");
        unitScaling = in.f("unitScaling");
        shields = in.f("shields");
        shieldScaling = in.f("shieldScaling");
        unitAmount = in.i("unitAmount");
        spawn = in.i("spawn");
        int[] stored = in.ints("payloads");
        if(stored == null){
            payloads = null;
        }else{
            payloads = new ArrayList<>(stored.length);
            for(int unit : stored) payloads.add(unit);
        }
        effect = in.i("effect");
        items = in.obj("items", ItemStack.class);
        team = in.i("team");
    }

    @Override
    public boolean equals(Object o){
        return o instanceof SpawnGroup g && end == g.end && begin == g.begin && spacing == g.spacing && max == g.max
            && Float.compare(g.unitScaling, unitScaling) == 0 && Float.compare(g.shields, shields) == 0
            && Float.compare(g.shieldScaling, shieldScaling) == 0 && unitAmount == g.unitAmount && type == g.type
            && effect == g.effect && Objects.equals(items, g.items);
    }

    @Override
    public int hashCode(){
        return Objects.hash(type, end, begin, spacing, max, unitScaling, shields, shieldScaling, unitAmount, effect, items);
    }
}
