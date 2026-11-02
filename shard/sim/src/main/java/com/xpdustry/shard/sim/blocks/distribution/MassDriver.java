package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code MassDriver.MassDriverBuild} (MassDriver.java): a powered turret that fires its items at another
 * mass driver of the same block and team within {@code range} world units, as a {@link MassDriverBolt} carrying them.
 *
 * <p>Its state machine ({@link #state}): an idle driver with shooters waiting on it and room for {@code minDistribute}
 * items starts {@code accepting}, else with a valid link starts {@code shooting}; idle or accepting, it dumps what it
 * holds. Accepting, it turns toward the first waiting shooter. Shooting, once it holds {@code minDistribute} items and
 * its link has room for as many, it queues itself in the link's {@link #waitingShooters}, turns toward it once
 * reloaded, and fires when it is the link's first shooter, the link is accepting and both face each other within 2°;
 * it is then idle at once, and the link stays accepting until the bolt should have arrived ({@link Arrived}, vanilla's
 * {@code Time.run}). Without power it only reloads and dumps.
 *
 * <p>A bolt that reaches its target hands it the items through {@link #handlePayload}, up to twice the capacity; the
 * rest drop when the bolt despawns. Vanilla saves the link, the turret's angle and the state; the waiting shooters
 * and the reload start empty after a load.
 *
 * <p>The turret's angle is vanilla's {@code rotation} float, which shadows the building's rotation; here it is
 * {@link #driverRotation}. Effects, shakes and sounds are client-side, but each sound's pitch draws from the random.
 * Not ported: the client's {@code onConfigureBuildTapped}.
 */
@Kind("mass-driver")
public final class MassDriver extends Building implements Checkpointable{
    static final Configs<MassDriver> CONFIGS = Configs.of(MassDriver.class)
        // point2 is relative
        .on(Point2.class, (driver, p) -> driver.link = Point2.pack(p.x() + driver.tileX(), p.y() + driver.tileY()))
        .on(Integer.class, (driver, i) -> driver.link = i);

    /** Vanilla's {@code DriverState}, by ordinal as saved. */
    public enum DriverState{
        /** Nothing is shooting at this mass driver and it does not have any target. */
        idle,
        /** Currently getting shot at, unload items. */
        accepting,
        shooting;

        public static final DriverState[] all = values();
    }

    /** The linked tile, {@link Point2#pack}ed, or -1. */
    public int link = -1;
    /** Vanilla's {@code rotation}: the turret's angle in degrees. */
    public float driverRotation = 90;
    public float reloadCounter;
    public DriverState state = DriverState.idle;
    /** Vanilla's {@code OrderedSet<Building>}: the drivers waiting to shoot here, in the order they asked. */
    public List<Building> waitingShooters = new ArrayList<>();

    @Derived("the block's parameters, read on first use")
    private DriverParams params;

    /** The block's driver fields, and its bullet type's content id. */
    record DriverParams(float range, float rotateSpeed, float translation, int minDistribute, float reload, int bullet,
                        float bulletSpeed, float bulletLifetime){
        static DriverParams of(BlockDef block, ContentEpoch content){
            if(!block.baked()) return new DriverParams(440f, 5f, 7f, 10, 200f, -1, 5.5f, 200f);
            var f = block.fields();
            String bullet = f.s("bullet");
            return new DriverParams(f.f("range"), f.f("rotateSpeed"), f.f("translation"), f.i("minDistribute"), f.f("reload"),
                bullet == null ? -1 : content.bullet(bullet).id(), f.f("bulletSpeed"), f.f("bulletLifetime"));
        }
    }

    DriverParams params(){
        if(params == null) params = DriverParams.of(block(), world().content());
        return params;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    /** {@code config()}: the link relative to this driver. */
    @Override
    public Point2 config(){
        return Point2.unpack(link).add(-tileX(), -tileY());
    }

    /** {@code currentShooter()}: the first waiting shooter, or null. */
    public Building currentShooter(){
        return waitingShooters.isEmpty() ? null : waitingShooters.getFirst();
    }

    /** {@code world.build(link)}: the building covering the linked tile, or null. */
    private Building linkBuild(){
        int x = Point2.x(link), y = Point2.y(link);
        return world().tiles().inBounds(x, y) ? world().buildings().at(x, y) : null;
    }

    @Override
    public void updateTile(){
        DriverParams params = params();
        Building link = linkBuild();
        boolean hasLink = linkValid();
        if(hasLink){
            this.link = link.pos();
        }
        // reload regardless of state
        if(reloadCounter > 0f){
            reloadCounter = Mathf.clamp(reloadCounter - edelta() / params.reload());
        }
        Building current = currentShooter();
        // cleanup waiting shooters that are not valid
        if(current != null && !shooterValid(current)){
            waitingShooters.remove(current);
        }
        int itemCapacity = info().itemCapacity;
        // switch states
        if(state == DriverState.idle){
            // start accepting when idle and there's space
            if(!waitingShooters.isEmpty() && (itemCapacity - items.total() >= params.minDistribute())){
                state = DriverState.accepting;
            }else if(hasLink){
                // switch to shooting if there's a valid link
                state = DriverState.shooting;
            }
        }
        // dump when idle or accepting
        if(state == DriverState.idle || state == DriverState.accepting){
            dumpAccumulate(-1);
        }
        // skip when there's no power
        if(efficiency <= 0f){
            return;
        }
        if(state == DriverState.accepting){
            // if there's nothing shooting at this, bail - OR, items full
            if(currentShooter() == null || (itemCapacity - items.total() < params.minDistribute())){
                state = DriverState.idle;
                return;
            }
            // align to shooter rotation
            Building shooter = currentShooter();
            driverRotation = Angles.moveToward(driverRotation, Angles.angle(x(), y(), shooter.x(), shooter.y()), params.rotateSpeed() * efficiency);
        }else if(state == DriverState.shooting){
            // if there's nothing to shoot at OR someone wants to shoot at this thing, bail
            if(!hasLink || (!waitingShooters.isEmpty() && (itemCapacity - items.total() >= params.minDistribute()))){
                state = DriverState.idle;
                return;
            }
            float targetRotation = Angles.angle(x(), y(), link.x(), link.y());
            if(items.total() >= params.minDistribute() // must shoot minimum amount of items
                && link.info().itemCapacity - link.items.total() >= params.minDistribute()){ // must have minimum amount of space
                var other = (MassDriver)link;
                if(!other.waitingShooters.contains(this)) other.waitingShooters.add(this);
                if(reloadCounter <= 0.0001f){
                    // align to target location
                    driverRotation = Angles.moveToward(driverRotation, targetRotation, params.rotateSpeed() * efficiency);
                    // fire when it's the first in the queue and angles are ready
                    if(other.currentShooter() == this && other.state == DriverState.accepting
                        && Angles.near(driverRotation, targetRotation, 2f) && Angles.near(other.driverRotation, targetRotation + 180f, 2f)){
                        // actually fire
                        fire(other);
                        float timeToArrive = Math.min(params.bulletLifetime() / timeScale, dst(other.x(), other.y()) / (params.bulletSpeed() * timeScale));
                        world().timers().run(timeToArrive, new Arrived(other, this));
                        // driver is immediately idle
                        state = DriverState.idle;
                    }
                }
            }
        }
    }

    /** The end of a shot ({@code Time.run(timeToArrive, …)}, MassDriver.java:216): the target stops waiting for it. */
    public record Arrived(MassDriver target, MassDriver shooter) implements Timers.Task{
        @Override
        public void run(World world){
            // remove waiting shooters, it's done firing
            target.waitingShooters.remove(shooter);
            target.state = DriverState.idle;
        }
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return Mathf.clamp(1f - reloadCounter);
        return super.sense(sensor);
    }

    @Override
    public boolean acceptItem(Building source, int item){
        // mass drivers that output only cannot accept items
        return items.total() < info().itemCapacity && linkValid();
    }

    /** {@code fire(target)} (MassDriver.java:299): empties up to a full load into a bolt aimed at {@code target}. */
    void fire(MassDriver target){
        DriverParams params = params();
        World world = world();
        // reset reload, use power
        reloadCounter = 1f;
        int[] carried = new int[world.content().items()];
        int totalUsed = 0;
        int capacity = info().itemCapacity;
        for(int i = 0; i < carried.length; i++){
            int maxTransfer = Math.min(items.get(i), capacity - totalUsed);
            carried[i] = maxTransfer;
            totalUsed += maxTransfer;
            items.remove(i, maxTransfer);
        }
        float angle = Angles.angle(x(), y(), target.x(), target.y());
        BulletType type = world.bullets().types(world).get(params.bullet());
        Bullet bullet = type.create(world, this, team, x() + Angles.trnsx(angle, params.translation()), y() + Angles.trnsy(angle, params.translation()),
            angle, totalUsed / 2f, params.bulletSpeed() * timeScale, params.bulletLifetime() / timeScale);
        if(bullet != null) DriverBolts.of(world).attach(bullet, this, target, carried);
        // shootSound.at(x, y, 1f + Mathf.range(0.2f), shootSoundVolume)
        Mathf.range(world.rand(), 0.2f);
    }

    /** {@code handlePayload(bullet, data)} (MassDriver.java:325): takes what fits of a bolt's items, then removes it. */
    public void handlePayload(Bullet bullet, int[] carried){
        int totalItems = items.total();
        int capacity = info().itemCapacity;
        // add all the items possible
        for(int i = 0; i < carried.length; i++){
            int maxAdd = Math.min(carried[i], capacity * 2 - totalItems);
            items.add(i, maxAdd);
            carried[i] -= maxAdd;
            totalItems += maxAdd;
            if(totalItems >= capacity * 2) break;
        }
        // receiveSound.at(x, y, 1f + Mathf.range(0.2f), shootSoundVolume)
        Mathf.range(world().rand(), 0.2f);
        reloadCounter = 1f;
        bullet.remove();
    }

    /** {@code shooterValid(other)}: a powered, standing driver of this block linked here, in range. */
    boolean shooterValid(Building other){
        return other instanceof MassDriver entity && other.isValid() && other.efficiency > 0 && entity.block() == block()
            && entity.link == pos() && within(other.x(), other.y(), params().range());
    }

    /** {@code linkValid()}: the linked building is a driver of this block and team, in range. */
    public boolean linkValid(){
        if(link == -1) return false;
        return linkBuild() instanceof MassDriver other && other.block() == block() && other.team == team
            && within(other.x(), other.y(), params().range());
    }

    @Override
    public void write(CheckpointOut out){
        out.i("link", link).f("driverRotation", driverRotation).f("reloadCounter", reloadCounter).e("state", state)
            .refs("waitingShooters", waitingShooters.toArray(new Building[0]), waitingShooters.size());
    }

    @Override
    public void read(CheckpointIn in){
        link = in.i("link");
        driverRotation = in.f("driverRotation");
        reloadCounter = in.f("reloadCounter");
        state = in.e("state", DriverState.class);
        waitingShooters = new ArrayList<>(Arrays.asList(in.refs("waitingShooters", Building.class)));
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (MassDriver)into;
        out.link = link;
        out.driverRotation = driverRotation;
        out.reloadCounter = reloadCounter;
        out.state = state;
        out.waitingShooters = new ArrayList<>(waitingShooters.size());
        for(Building shooter : waitingShooters) out.waitingShooters.add(context.map(shooter));
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(link).f(driverRotation).f(reloadCounter).i(state.ordinal()).i(waitingShooters.size());
        for(Building shooter : waitingShooters) hasher.i(shooter.id());
    }
}
