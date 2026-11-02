package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code TargetDummy.TargetDummyBuild} (TargetDummy.java): a sandbox block that keeps a {@code dummy} unit
 * tethered over itself and counts the damage that unit takes. The building itself takes no damage and lets bullets
 * pass; its unit takes no health and hands each hit to it ({@link DummyBuilding}). When the unit is gone it makes a new
 * one at once ({@link SandboxEvent.TetherSpawned}). Every tick it sets the unit's boost, armor, team and hit size from
 * its config and pulls it back toward the block. Hits reset the {@code resetTime} countdown, after which the totals
 * clear; every 20 ticks it refreshes the totals it displays.
 *
 * <p>Configured with an {@code int[5]} (wire tag 21, {@link ConfigValue.IntArray}; an {@code IntSeq} is no config): enemy team (0/1), boosting (0/1), armor, reset time in ticks, and the hit
 * size's float bits. {@link #config()} gives it back.
 *
 * <p>A loaded dummy finds its unit by id on its first update ({@link #readUnitId}). Vanilla quirk, kept: until then
 * its save writes no unit, so a save made right after a load makes a second unit on the next load.
 */
@Kind("target-dummy")
public final class TargetDummy extends Building implements BulletCollider, DummyBuilding, Checkpointable{
    static final float MAX_DUMMY_SIZE = 30f;
    static final Configs<TargetDummy> CONFIGS = Configs.of(TargetDummy.class).on(ConfigValue.IntArray.class, TargetDummy::configure);

    /** The unit's id as read from a save, until the first update finds it; -1 otherwise. */
    public int readUnitId = -1;
    public Unit unit;
    public float resetTime = 120f;
    public float total, reset = resetTime, time, dummySize = 12f;
    public float dps, totalDisplay, timeDisplay;
    public int hits;
    public int hitsDisplay;
    public boolean boosting;
    public float unitArmor;
    public int unitTeam = VanillaIds.DERELICT;
    @Derived("the block's unitType, dpsUpdateTime and pullScale, read on first use")
    private Params params;

    private record Params(UnitDef unitType, int dpsUpdateTime, float pullScale){
    }

    /** The config handler (TargetDummy.java:46): anything but five values is ignored. */
    private static void configure(TargetDummy tile, ConfigValue.IntArray value){
        int[] config = value.ints();
        // invalid, must have 5 values exactly
        if(config.length != 5) return;
        tile.unitTeam = tile.team;
        if(config[0] == 1) tile.unitTeam = tile.dummyTeam();
        tile.boosting = config[1] == 1;
        tile.unitArmor = Mathf.clamp(config[2], 0, 100_000);
        tile.resetTime = config[3];
        tile.dummySize = Mathf.clamp(Float.intBitsToFloat(config[4]), 0.1f, MAX_DUMMY_SIZE * Footprint.TILE_SIZE);
        if(Float.isNaN(tile.dummySize) || Float.isInfinite(tile.dummySize)) tile.dummySize = 8f;
    }

    @Override
    public void created(){
        if(unitTeam == VanillaIds.DERELICT) unitTeam = team;
    }

    @Override
    public void updateTile(){
        Params p = params();
        World world = world();
        // unit was lost/destroyed somehow
        if(unit != null && (unit.dead || !unit.isAdded())) unit = null;
        if(readUnitId != -1){
            EntityGroups groups = EntityGroups.of(world);
            unit = groups != null && groups.byId(readUnitId) instanceof Unit found ? found : null;
            readUnitId = -1;
        }
        if(unitTeam == VanillaIds.DERELICT) unitTeam = team;
        if(unit == null){
            unit = Unit.create(world, p.unitType, team);
            unit.tether = this;
            unit.set(x(), y());
            unit.rotation = 90f;
            unit.add();
            world.publish(new SandboxEvent.TetherSpawned(world.ticks(), tile(), unit.id()));
        }
        unit.updateBoosting(boosting);
        unit.armor = unitArmor;
        unit.team = unitTeam;
        unit.hitSize = dummySize;
        // similar to impulseNet but does not factor in mass
        var pull = new Vec2(x(), y()).sub(unit.x, unit.y).limit(dst(unit.x, unit.y) * p.pullScale);
        unit.vel.add(pull);
        // a player never controls it: never remote, so it isn't moved here
        if(unit.moving()) unit.lookAt(unit.vel.angle());

        time += 1f;
        reset += 1f;
        if(timer(p.dpsUpdateTime, 20)){
            dps = total / time * 60f;
            totalDisplay = total;
            timeDisplay = time / 60f;
            hitsDisplay = hits;
        }
        if(reset >= resetTime){
            hits = 0;
            total = time = dps = 0;
        }
    }

    @Override
    public void dummyHit(float damage){
        reset = 0f;
        total += damage;
        hits++;
    }

    /** {@code dummyTeam()}: back to its own team, or the wave team, or the next team when it is the wave team. */
    public int dummyTeam(){
        // return to own team
        if(unitTeam != team) return team;
        int waveTeam = rules().waveTeam;
        return team != waveTeam ? waveTeam : (team % (256 - 1)) + 1;
    }

    /** Hit the unit, not the building. */
    @Override
    public boolean collide(Bullet bullet){
        return false;
    }

    @Override
    public boolean collision(Bullet bullet){
        return false;
    }

    @Override
    public void damage(float damage){
        // just in case
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public ConfigValue.IntArray config(){
        return new ConfigValue.IntArray(new int[]{Mathf.num(unitTeam != team), Mathf.num(boosting), (int)unitArmor, (int)resetTime, Float.floatToIntBits(dummySize)});
    }

    private Params params(){
        if(params == null){
            BlockDef block = block();
            var f = block.fields();
            params = new Params(world().content().unit(block.baked() ? f.s("unitType") : "dummy"),
                block.baked() ? f.i("dpsUpdateTime") : 1, block.baked() ? f.f("pullScale") : 0.33f);
        }
        return params;
    }

    /** The unit's armor and hit size are its type's after a restore ({@code restoreType}); this dummy sets them. */
    @Override
    public void restored(){
        if(unit != null){
            unit.armor = unitArmor;
            unit.hitSize = dummySize;
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.i("readUnitId", readUnitId).ref("unit", unit).f("resetTime", resetTime).f("total", total).f("reset", reset).f("time", time)
            .f("dummySize", dummySize).f("dps", dps).f("totalDisplay", totalDisplay).f("timeDisplay", timeDisplay).i("hits", hits)
            .i("hitsDisplay", hitsDisplay).bool("boosting", boosting).f("unitArmor", unitArmor).i("unitTeam", unitTeam);
    }

    @Override
    public void read(CheckpointIn in){
        readUnitId = in.i("readUnitId");
        unit = in.ref("unit", Unit.class);
        resetTime = in.f("resetTime");
        total = in.f("total");
        reset = in.f("reset");
        time = in.f("time");
        dummySize = in.f("dummySize");
        dps = in.f("dps");
        totalDisplay = in.f("totalDisplay");
        timeDisplay = in.f("timeDisplay");
        hits = in.i("hits");
        hitsDisplay = in.i("hitsDisplay");
        boosting = in.bool("boosting");
        unitArmor = in.f("unitArmor");
        unitTeam = in.i("unitTeam");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (TargetDummy)into;
        out.readUnitId = readUnitId;
        out.unit = context.map(unit);
        out.resetTime = resetTime;
        out.total = total;
        out.reset = reset;
        out.time = time;
        out.dummySize = dummySize;
        out.dps = dps;
        out.totalDisplay = totalDisplay;
        out.timeDisplay = timeDisplay;
        out.hits = hits;
        out.hitsDisplay = hitsDisplay;
        out.boosting = boosting;
        out.unitArmor = unitArmor;
        out.unitTeam = unitTeam;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(readUnitId).i(unit == null ? -1 : unit.id()).f(resetTime).f(total).f(reset).f(time).f(dummySize).f(dps)
            .f(totalDisplay).f(timeDisplay).i(hits).i(hitsDisplay).bool(boosting).f(unitArmor).i(unitTeam);
    }

    /** {@code canPickup()}: payload units may lift it never. */
    @Override
    public boolean canPickup(){
        return false;
    }

    @Override
    public void recreated(){
        created();
    }
}
