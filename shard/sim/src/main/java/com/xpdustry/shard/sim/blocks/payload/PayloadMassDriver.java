package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code PayloadMassDriver.PayloadDriverBuild} (PayloadMassDriver.java:108), the payload mass driver and the
 * large one: a powered turret that throws the payload it holds (a building or a unit that fits {@code maxPayloadSize})
 * to another driver of the same block and team within {@code range}.
 *
 * <p>Its state machine ({@link #state}) is the item mass driver's: idle, it starts {@code accepting} when shooters wait
 * on it and it holds nothing, else {@code shooting} with a valid link; idle or accepting, it moves out what it holds.
 * Shooting, it pulls its payload in and then out along its barrel ({@code payLength}, to {@code length} less the recoil),
 * queues itself on its link, turns to it once reloaded, and charges while it is the link's first shooter, the link is
 * accepting and reloaded, and both face each other within 1°; after {@code chargeTime} it hands the payload over at
 * once. There is no projectile: the payload sits in the receiver's barrel from that tick, drawn in flight by the
 * transfer effect, and the receiver waits {@code effectDelayTimer} (the effect's 11 ticks) before it recoils and starts
 * moving it out. The shot's and the arrival's sounds draw their pitch; their effects and shakes draw nothing on a
 * server. Without power it only reloads, discharges and moves its payload out.
 *
 * <p>State, by vanilla's names: {@code link}, {@code turretRotation}, {@code reloadCounter}, {@code charge},
 * {@code targetSize}, {@code curSize}, {@code payLength}, {@code effectDelayTimer}, {@code lastOther}, {@code loaded},
 * {@code charging}, {@code state}, {@code waitingShooters}, {@code recPayload}. Saved at revision 1 after the payload
 * block's body: the link, the turret's angle, the state, the reload, the charge, {@code loaded} and {@code charging}; the
 * rest starts afresh after a load. Not ported: the client's {@code onConfigureBuildTapped}.
 */
@Kind("payload-mass-driver")
public final class PayloadMassDriver extends PayloadBuild<Payload>{
    static final Configs<PayloadMassDriver> CONFIGS = Configs.of(PayloadMassDriver.class)
        // point2 is relative
        .on(Point2.class, (tile, point) -> tile.link = Point2.pack(point.x() + tile.tileX(), point.y() + tile.tileY()))
        .on(Integer.class, (tile, point) -> tile.link = point);

    /** Vanilla's {@code PayloadDriverState}, by ordinal as saved. */
    public enum PayloadDriverState{
        idle, accepting, shooting;

        public static final PayloadDriverState[] all = values();
    }

    /** The linked tile, {@link Point2#pack}ed, or -1. */
    public int link = -1;
    public float turretRotation = 90;
    public float reloadCounter, charge;
    public float targetSize = Float.NaN, curSize = Float.NaN;
    public float payLength, effectDelayTimer = -1f;
    public PayloadMassDriver lastOther;
    public boolean loaded;
    public boolean charging;
    public PayloadDriverState state = PayloadDriverState.idle;
    /** Vanilla's {@code Queue<Building>}: the drivers waiting to shoot here, in the order they asked. */
    public List<Building> waitingShooters = new ArrayList<>();
    @Derived("set only within a shot, so null between ticks")
    public Payload recPayload;

    @Derived("the block's parameters, read on first use")
    private DriverParams params;

    /** The block's driver fields. */
    record DriverParams(float range, float rotateSpeed, float length, float knockback, float reload, float chargeTime,
                        float maxPayloadSize, float grabWidth){
        static DriverParams of(BlockDef block){
            if(!block.baked()) return new DriverParams(100f, 5f, 89 / 8f, 5f, 30f, 100f, 3f, 8f);
            var f = block.fields();
            return new DriverParams(f.f("range"), f.f("rotateSpeed"), f.f("length"), f.f("knockback"), f.f("reload"), f.f("chargeTime"),
                f.f("maxPayloadSize"), f.f("grabWidth"));
        }
    }

    DriverParams params(){
        if(params == null) params = DriverParams.of(block());
        return params;
    }

    /** {@code targetSize = grabWidth * 2f, curSize = targetSize}: field initializers that read the block. */
    @Override
    public void created(){
        super.created();
        targetSize = curSize = params().grabWidth() * 2f;
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

    @Override
    public boolean acceptUnitPayload(Unit unit){
        return unit != null && unit.hitSize <= params().maxPayloadSize() * Footprint.TILE_SIZE;
    }

    /** {@code world.build(link)}: the building covering the linked tile, or null. */
    private Building linkBuild(){
        int x = Point2.x(link), y = Point2.y(link);
        return world().tiles().inBounds(x, y) ? world().buildings().at(x, y) : null;
    }

    private float angleTo(Building other){
        return Angles.angle(x(), y(), other.x(), other.y());
    }

    @Override
    public void updateTile(){
        super.updateTile();
        DriverParams params = params();
        Building link = linkBuild();
        boolean hasLink = linkValid();
        // discharge when charging isn't happening
        if(!charging){
            charge -= 10f;
            if(charge < 0) charge = 0f;
        }
        curSize = Mathf.lerpDelta(curSize, targetSize, 0.05f);
        targetSize = params.grabWidth() * 2f;
        if(payload != null){
            targetSize = payload.size();
        }
        boolean pos = effectDelayTimer > 0;
        effectDelayTimer -= 1f;
        if(effectDelayTimer <= 0 && pos && lastOther != null){
            reloadCounter = 1f;
            // receiveSound.at(x, y, 1f + Mathf.range(0.2f), shootSoundVolume)
            Mathf.range(world().rand(), 0.2f);
        }
        charging = false;
        if(hasLink){
            this.link = link.pos();
        }
        // reload regardless of state
        reloadCounter -= edelta() / params.reload();
        if(reloadCounter < 0) reloadCounter = 0f;
        Building current = currentShooter();
        // cleanup waiting shooters that are not valid
        if(current != null && !(current instanceof PayloadMassDriver entity && current.isValid() && entity.efficiency > 0
            && entity.block() == block() && entity.link == pos() && within(current.x(), current.y(), params.range()))){
            waitingShooters.removeFirst();
        }
        // switch states
        if(state == PayloadDriverState.idle){
            // start accepting when idle and there's space
            if(!waitingShooters.isEmpty() && payload == null){
                state = PayloadDriverState.accepting;
            }else if(hasLink){
                // switch to shooting if there's a valid link
                state = PayloadDriverState.shooting;
            }
        }
        // dump when idle or accepting
        if((state == PayloadDriverState.idle || state == PayloadDriverState.accepting) && payload != null){
            if(loaded){
                payLength -= payloadSpeed() * delta();
                if(payLength <= 0f){
                    loaded = false;
                    payVector.setZero();
                    payRotation = Angles.moveToward(payRotation, turretRotation + 180f, payloadRotateSpeed() * delta());
                }
            }else if(effectDelayTimer <= 0){
                moveOutPayload();
            }
        }
        // skip when there's no power
        if(efficiency <= 0f){
            return;
        }
        if(state == PayloadDriverState.accepting){
            // if there's nothing shooting at this or items are full, bail out
            if(currentShooter() == null || payload != null){
                state = PayloadDriverState.idle;
                return;
            }
            if(currentShooter().getPayload() != null){
                targetSize = recPayload == null ? currentShooter().getPayload().size() : recPayload.size();
            }
            // align to shooter rotation
            turretRotation = Angles.moveToward(turretRotation, angleTo(currentShooter()), params.rotateSpeed() * efficiency);
        }else if(state == PayloadDriverState.shooting){
            // if there's nothing to shoot at OR someone wants to shoot at this thing, bail
            if(!hasLink || (!waitingShooters.isEmpty() && payload == null)){
                state = PayloadDriverState.idle;
                return;
            }
            float targetRotation = angleTo(link);
            boolean movedOut = false;
            payRotation = Angles.moveToward(payRotation, turretRotation, payloadRotateSpeed() * delta());
            if(loaded){
                float loadLength = params.length() - reloadCounter * params.knockback();
                payLength += payloadSpeed() * delta();
                if(payLength >= loadLength){
                    payLength = loadLength;
                    movedOut = true;
                }
            }else if(moveInPayload()){
                payLength = 0f;
                loaded = true;
            }
            // make sure payload firing can happen
            if(movedOut && payload != null && link.getPayload() == null){
                var other = (PayloadMassDriver)link;
                if(!other.waitingShooters.contains(this)){
                    other.waitingShooters.addLast(this);
                }
                if(reloadCounter <= 0){
                    // align to target location
                    turretRotation = Angles.moveToward(turretRotation, targetRotation, params.rotateSpeed() * efficiency);
                    // fire when it's the first in the queue and angles are ready
                    if(other.currentShooter() == this && other.state == PayloadDriverState.accepting && other.reloadCounter <= 0f
                        && Angles.within(turretRotation, targetRotation, 1f) && Angles.within(other.turretRotation, targetRotation + 180f, 1f)){
                        charge += edelta();
                        charging = true;
                        if(charge >= params.chargeTime()){
                            fire(other);
                        }
                    }
                }
            }
        }
    }

    /** The shot (PayloadMassDriver.java:309-357): the payload goes into {@code other}'s barrel at once. */
    private void fire(PayloadMassDriver other){
        DriverParams params = params();
        float cx = Angles.trnsx(turretRotation, params.length()), cy = Angles.trnsy(turretRotation, params.length());
        // shootSound.at(x, y, Mathf.random(0.9f, 1.1f), shootSoundVolume)
        Mathf.random(world().rand(), 0.9f, 1.1f);
        Payload pay = payload;
        other.recPayload = payload;
        // transferEffect.lifetime
        other.effectDelayTimer = 11f;
        // transfer payload
        other.handlePayload(this, pay);
        other.lastOther = this;
        other.payVector.set(-cx, -cy);
        other.payRotation = turretRotation;
        other.payLength = params.length();
        other.loaded = true;
        other.updatePayload();
        other.recPayload = null;
        if(!other.waitingShooters.isEmpty() && other.waitingShooters.getFirst() == this){
            other.waitingShooters.removeFirst();
        }
        other.state = PayloadDriverState.idle;
        // reset state after shooting immediately
        payload = null;
        payLength = 0f;
        loaded = false;
        state = PayloadDriverState.idle;
        reloadCounter = 1f;
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return Mathf.clamp(1f - reloadCounter / params().reload());
        return super.sense(sensor);
    }

    /** {@code updatePayload()}: a loaded payload sits along the barrel, else at its offset. */
    @Override
    public void updatePayload(){
        if(payload != null){
            if(loaded){
                payload.set(x() + Angles.trnsx(turretRotation, payLength), y() + Angles.trnsy(turretRotation, payLength), payRotation);
            }else{
                payload.set(x() + payVector.x, y() + payVector.y, payRotation);
            }
        }
    }

    @Override
    public boolean acceptPayload(Building source, Payload payload){
        return super.acceptPayload(source, payload) && payload.size() <= params().maxPayloadSize() * Footprint.TILE_SIZE;
    }

    /** {@code linkValid()}: the linked building is a driver of this block and team, in range. */
    public boolean linkValid(){
        if(link == -1) return false;
        return linkBuild() instanceof PayloadMassDriver other && other.block() == block() && other.team == team
            && within(other.x(), other.y(), params().range());
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PayloadMassDriver)into;
        out.link = link;
        out.turretRotation = turretRotation;
        out.reloadCounter = reloadCounter;
        out.charge = charge;
        out.targetSize = targetSize;
        out.curSize = curSize;
        out.payLength = payLength;
        out.effectDelayTimer = effectDelayTimer;
        out.lastOther = context.map(lastOther);
        out.loaded = loaded;
        out.charging = charging;
        out.state = state;
        out.waitingShooters = new ArrayList<>(waitingShooters.size());
        for(Building shooter : waitingShooters) out.waitingShooters.add(context.map(shooter));
        // only set inside the shot, so always null between ticks
        out.recPayload = null;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(link).f(turretRotation).f(reloadCounter).f(charge).f(targetSize).f(curSize).f(payLength).f(effectDelayTimer)
            .i(lastOther == null ? -1 : lastOther.id()).bool(loaded).bool(charging).i(state.ordinal()).i(waitingShooters.size());
        for(Building shooter : waitingShooters) hasher.i(shooter.id());
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.i("link", link).f("turretRotation", turretRotation).f("reloadCounter", reloadCounter).f("charge", charge)
            .f("targetSize", targetSize).f("curSize", curSize).f("payLength", payLength).f("effectDelayTimer", effectDelayTimer)
            .ref("lastOther", lastOther).bool("loaded", loaded).bool("charging", charging).e("state", state)
            .refs("waitingShooters", waitingShooters.toArray(new Building[0]), waitingShooters.size());
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        link = in.i("link");
        turretRotation = in.f("turretRotation");
        reloadCounter = in.f("reloadCounter");
        charge = in.f("charge");
        targetSize = in.f("targetSize");
        curSize = in.f("curSize");
        payLength = in.f("payLength");
        effectDelayTimer = in.f("effectDelayTimer");
        lastOther = in.ref("lastOther", PayloadMassDriver.class);
        loaded = in.bool("loaded");
        charging = in.bool("charging");
        state = in.e("state", PayloadDriverState.class);
        waitingShooters = new ArrayList<>(Arrays.asList(in.refs("waitingShooters", Building.class)));
    }
}
