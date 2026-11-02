package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PayloadDeconstructor.PayloadDeconstructorBuild} (PayloadDeconstructor.java:56), the small and the
 * large deconstructor: it takes a payload that costs something and fits, unless the items it would give back don't fit
 * (a payload costing at least {@code itemBuffer} of an item is taken anyway), pulls it to its centre, then takes it apart
 * at {@code deconstructSpeed / buildTime} per tick of efficiency, giving back its requirements (times the rules' build
 * or unit cost) into its own items as they reach whole numbers, which it dumps {@code dumpRate} times a tick. A building
 * with liquid pours it onto the floor under the deconstructor as it begins. It stalls while any requirement's item is
 * full.
 *
 * <p>State, by vanilla's names: {@code deconstructing} (the payload being taken apart), {@code accum} (the items owed,
 * per requirement, or null), {@code progress}, {@code time}, {@code speedScl} and {@code overlayTime}. Saved after the
 * payload block's body: {@code progress}, {@code accum} (a short count, then the floats), {@code deconstructing}. The
 * effects and the warning overlay's fade only draw; {@code overlayTime} is kept because {@code acceptPayload} sets it.
 */
@Kind("payload-deconstructor")
public final class PayloadDeconstructor extends PayloadBuild<Payload>{
    public Payload deconstructing;
    public float[] accum;
    public float progress;
    public float time, speedScl;
    public float overlayTime;

    @Derived("the block's parameters, read on first use")
    private float maxPayloadSize = Float.NaN, deconstructSpeed;
    @Derived("the block's parameters, read on first use")
    private int dumpRate, itemBuffer;

    private void params(){
        if(!Float.isNaN(maxPayloadSize)) return;
        BlockDef block = block();
        if(block.baked()){
            maxPayloadSize = block.fields().f("maxPayloadSize");
            deconstructSpeed = block.fields().f("deconstructSpeed");
            dumpRate = block.fields().i("dumpRate");
            itemBuffer = block.fields().i("itemBuffer");
        }else{
            maxPayloadSize = 4f;
            deconstructSpeed = 2.5f;
            dumpRate = 4;
            itemBuffer = 0;
        }
    }

    /** The cost multiplier of {@code payload}'s requirements: the rules' build cost for a building, unit cost for a unit. */
    private float costMultiplier(Payload payload){
        return payload instanceof Payload.BuildPayload ? rules().buildCostMultiplier : rules().unitCost(team);
    }

    @Override
    public boolean acceptUnitPayload(Unit unit){
        params();
        return payload == null && deconstructing == null && unit.type().allowedInPayloads() && !unit.spawnedByCore
            && PayloadCosts.unitRequirements(world().content(), unit.type().id()).size() > 0
            && unit.hitSize / Footprint.TILE_SIZE <= maxPayloadSize;
    }

    @Override
    public void handlePayload(Building source, Payload payload){
        super.handlePayload(source, payload);
        accum = null;
    }

    /**
     * {@code acceptPayload}: from anything but itself (a unit entering), refused while one of the items it would give
     * back would reach the capacity, unless a requirement before it reaches the buffer. Vanilla quirk kept: the loop
     * stops at the first requirement that reaches the buffer, accepting, even if an earlier one was full.
     */
    @Override
    public boolean acceptPayload(Building source, Payload payload){
        params();
        boolean isFull = false;
        Stacks reqs = PayloadCosts.requirements(world(), payload);
        // source equalling this building means it comes from a unit payenter
        if(source != this){
            float multiplier = costMultiplier(payload);
            int capacity = info().itemCapacity;
            for(int i = 0; i < reqs.size(); i++){
                float realAmount = reqs.amount(i) * multiplier;
                if(items.get(reqs.id(i)) + realAmount >= capacity) isFull = true;
                // if the payload's requirements are too large within the buffer, receive anyway
                if(realAmount >= itemBuffer){
                    isFull = false;
                    break;
                }
            }
            if(isFull && deconstructing == null){
                overlayTime = 2.5f;
            }
        }
        return !isFull && deconstructing == null && this.payload == null && super.acceptPayload(source, payload) && reqs.size() > 0
            && payload.fits(maxPayloadSize);
    }

    @Override
    public void updateTile(){
        params();
        super.updateTile();
        if(items.total() > 0){
            for(int i = 0; i < dumpRate; i++){
                dumpAccumulate(-1);
            }
        }
        if(deconstructing == null){
            progress = 0f;
        }
        payRotation = Angles.moveToward(payRotation, 90f, payloadRotateSpeed() * edelta());
        if(deconstructing != null){
            Stacks reqs = PayloadCosts.requirements(world(), deconstructing);
            if(accum == null || reqs.size() != accum.length){
                accum = new float[reqs.size()];
            }
            int capacity = info().itemCapacity;
            // check if there is enough space to get the items for deconstruction
            boolean canProgress = true;
            for(int i = 0; i < reqs.size(); i++){
                if(items.get(reqs.id(i)) >= capacity){
                    canProgress = false;
                    break;
                }
            }
            float multiplier = costMultiplier(deconstructing);
            // move progress forward if possible
            if(canProgress){
                float shift = edelta() * deconstructSpeed / PayloadCosts.buildTime(world(), deconstructing);
                float realShift = Math.min(shift, 1f - progress);
                // if began deconstruction...
                if(progress == 0f && shift > 0f && deconstructing instanceof Payload.BuildPayload pay){
                    Building build = pay.building();
                    // dump liquid on floor (does not respect block configuration with respect to dumping liquids on floor)
                    if(build.liquids != null && build.liquids.currentAmount() > 0){
                        int size = block().size();
                        float perCell = build.liquids.currentAmount() / (size * size) * 2f;
                        int liquid = build.liquids.current();
                        Footprint.each(world().tiles(), tileX(), tileY(), size, other -> Puddles.deposit(world(), other, liquid, perCell));
                    }
                }
                progress += shift;
                time += edelta();
                for(int i = 0; i < reqs.size(); i++){
                    accum[i] += reqs.amount(i) * multiplier * realShift;
                }
            }
            speedScl = Mathf.lerpDelta(speedScl, canProgress ? 1f : 0f, 0.1f);
            // transfer items from accumulation buffer into block inventory when they reach integers
            for(int i = 0; i < reqs.size(); i++){
                int taken = Math.min((int)accum[i], capacity - items.get(reqs.id(i)));
                if(taken > 0){
                    items.add(reqs.id(i), taken);
                    accum[i] -= taken;
                }
            }
            // finish deconstruction, prepare for next payload
            if(progress >= 1f){
                canProgress = true;
                // check for rounding errors
                for(int i = 0; i < reqs.size(); i++){
                    if(Mathf.equal(accum[i], 1f, 0.0001f)){
                        if(items.total() < capacity){
                            items.add(reqs.id(i), 1);
                            accum[i] = 0f;
                        }else{
                            canProgress = false;
                            break;
                        }
                    }
                }
                if(canProgress){
                    deconstructing = null;
                    accum = null;
                }
            }
        }else if(moveInPayload(false) && payload != null){
            accum = new float[PayloadCosts.requirements(world(), payload).size()];
            deconstructing = payload;
            payload = null;
            progress = 0f;
        }
    }

    /** {@code sense(content)}: what it takes apart, else what it holds. */
    @Override
    public double sense(ContentType type, int id){
        if(deconstructing != null){
            return deconstructing.content().type() == type && deconstructing.content().id() == id ? 1 : 0;
        }
        return super.sense(type, id);
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return progress;
        return super.sense(sensor);
    }

    @Override
    public boolean shouldConsume(){
        return deconstructing != null && enabled;
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PayloadDeconstructor)into;
        out.deconstructing = deconstructing == null ? null : deconstructing.copy(context);
        out.accum = accum == null ? null : accum.clone();
        out.progress = progress;
        out.time = time;
        out.speedScl = speedScl;
        out.overlayTime = overlayTime;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(deconstructing != null);
        if(deconstructing != null) deconstructing.hash(hasher);
        hasher.i(accum == null ? -1 : accum.length);
        if(accum != null) hasher.floats(accum);
        hasher.f(progress).f(time).f(speedScl).f(overlayTime);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.obj("deconstructing", deconstructing).floats("accum", accum).f("progress", progress).f("time", time).f("speedScl", speedScl).f("overlayTime", overlayTime);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        deconstructing = in.obj("deconstructing", Payload.class);
        accum = in.floats("accum");
        progress = in.f("progress");
        time = in.f("time");
        speedScl = in.f("speedScl");
        overlayTime = in.f("overlayTime");
    }
}
