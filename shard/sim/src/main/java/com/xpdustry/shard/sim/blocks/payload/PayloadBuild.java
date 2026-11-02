package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PayloadBlock.PayloadBlockBuild} (PayloadBlock.java:96), the base of every block that holds one
 * payload and moves it in and out: unit factories and reconstructors (WP66), the payload mass driver, deconstructors,
 * constructors, loaders and the payload source and void (WP80). It is not a kind: each subclass is.
 *
 * <p>State, by vanilla's names: the payload ({@code T}), {@code payVector} (its offset from the centre),
 * {@code payRotation} and {@code carried}. Its save chunk is {@code payVector}, {@code payRotation}, then
 * {@code Payload.write} ({@code compat.v160.blocks.payload.PayloadBodies}); a subclass writes its fields after.
 *
 * <p>A unit entering it ({@link #onControlSelect}, from a player's selection or {@code ucontrol payEnter}) gets a new
 * entity id posted to the next frame ({@link Payloads#handleUnitPayload}). Not ported: the drawing
 * ({@code drawPayload}, {@code blends}).
 *
 * @param <T> the payloads it holds
 */
public abstract class PayloadBuild<T extends Payload> extends Building implements Checkpointable{
    public T payload;
    /** {@code payVector}: the payload's offset from the centre. */
    public Vec2 payVector = new Vec2();
    public float payRotation;
    /**
     * Set once a unit picked the block up. Vanilla quirk: only {@code drawTeamTop} clears it, which a server never
     * runs, so a block that was ever carried keeps its payload when it is removed.
     */
    public boolean carried;

    @Derived("payloadSpeed, read from the block on first use")
    private float payloadSpeed = Float.NaN;
    @Derived("payloadRotateSpeed, read from the block on first use")
    private float payloadRotateSpeed;

    /** {@code payloadSpeed}: how far the payload moves a tick. */
    protected float payloadSpeed(){
        if(Float.isNaN(payloadSpeed)){
            payloadSpeed = block().baked() ? block().fields().f("payloadSpeed") : 0.7f;
            payloadRotateSpeed = block().baked() ? block().fields().f("payloadRotateSpeed") : 3f;
        }
        return payloadSpeed;
    }

    /** {@code payloadRotateSpeed}: how far the payload turns a tick, in degrees. */
    protected float payloadRotateSpeed(){
        payloadSpeed();
        return payloadRotateSpeed;
    }

    /** {@code acceptUnitPayload(unit)}: whether a unit may enter as a payload. */
    public boolean acceptUnitPayload(Unit unit){
        return false;
    }

    /** {@code canControlSelect(unit)}: ground units near a solid block, others standing on it. */
    @Override
    public boolean canControlSelect(Unit unit){
        if(unit.spawnedByCore || !unit.type().allowedInPayloads() || payload != null || !acceptUnitPayload(unit)) return false;
        // ground units can be accepted when they are near solid blocks
        if(unit.isGrounded() && !unit.params().allowLegStep() && block().solid()){
            return unit.within(x(), y(), block().size() * Footprint.TILE_SIZE * 0.7f + unit.hitSize / 2f);
        }
        int on = unit.tileOn();
        return on != -1 && world().buildings().at(on) == this;
    }

    @Override
    public boolean acceptPayload(Building source, Payload payload){
        return this.payload == null;
    }

    /** {@code handlePayload}: a unit keeps where it stood, near the block; anything else comes from the source's side. */
    /**
     * {@code onControlSelect(unit)} (PayloadBlock.java:111): the unit enters as this block's payload, where it stood
     * (clamped to the block) and facing its way.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void onControlSelect(Unit unit){
        float ux = unit.x, uy = unit.y;
        payload = (T)Payloads.handleUnitPayload(unit);
        float half = block().size() * Footprint.TILE_SIZE / 2f;
        payVector.set(ux, uy).sub(x(), y()).clamp(-half, -half, half, half);
        payRotation = unit.rotation;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handlePayload(Building source, Payload payload){
        float half = block().size() * Footprint.TILE_SIZE / 2f;
        if(payload instanceof Payload.UnitPayload up){
            Unit unit = up.unit();
            float clampPos = block().size() * Footprint.TILE_SIZE * 0.7f + unit.hitSize / 2f;
            payVector.set(unit.x, unit.y).sub(x(), y()).clamp(-clampPos, -clampPos, clampPos, clampPos);
        }else{
            payVector.set(source.x(), source.y()).sub(x(), y()).clamp(-half, -half, half, half);
        }
        this.payload = (T)payload;
        payRotation = payload.rotation();
        updatePayload();
    }

    @Override
    public Payload getPayload(){
        return payload;
    }

    @Override
    public void pickedUp(){
        carried = true;
    }

    @Override
    public Payload takePayload(){
        T t = payload;
        payload = null;
        return t;
    }

    @Override
    public void onRemoved(){
        super.onRemoved();
        if(payload != null && !carried) payload.dump();
    }

    @Override
    public void updateTile(){
        if(payload != null){
            payload.update(null, this);
            if(payload.isDead()) payload = null;
        }
    }

    @Override
    public void onDestroyed(){
        if(payload != null) payload.destroyed();
        super.onDestroyed();
    }

    /** {@code updatePayload()}: the payload follows the block, at its offset and rotation. */
    public void updatePayload(){
        if(payload != null) payload.set(x() + payVector.x, y() + payVector.y, payRotation);
    }

    /** {@code moveInPayload()}. */
    public boolean moveInPayload(){
        return moveInPayload(true);
    }

    /** {@code moveInPayload(rotate)}: the payload moves (and turns) to the centre; whether it is there. */
    public boolean moveInPayload(boolean rotate){
        if(payload == null) return false;
        updatePayload();
        if(rotate) payRotation = Angles.moveToward(payRotation, block().rotate() ? rotdeg() : 90f, payloadRotateSpeed() * delta());
        payVector.approach(new Vec2(), payloadSpeed() * delta());
        return hasArrived();
    }

    /**
     * {@code moveOutPayload()}: the payload moves out through the front, pushing units aside if it will be dumped, then
     * goes to the building in front if that moves or takes payloads, else onto the ground.
     */
    public void moveOutPayload(){
        if(payload == null) return;
        updatePayload();
        float half = block().size() * Footprint.TILE_SIZE / 2f;
        Vec2 dest = new Vec2().trns(rotdeg(), half);
        payRotation = Angles.moveToward(payRotation, rotdeg(), payloadRotateSpeed() * delta());
        payVector.approach(dest, payloadSpeed() * delta());
        Building front = front();
        boolean canDump = front == null || !world().tiles().solid(front.tile());
        boolean canMove = front != null && (front.block().outputsPayload() || front.block().acceptsPayload());
        if(canDump && !canMove){
            // vanilla quirk: dest is Tmp.v1, which a push overwrites, so a block that pushed a unit doesn't arrive
            Vec2 pushed = PayloadBlock.pushOutput(world(), payload, 1f - (payVector.dst(dest) / half));
            if(pushed != null) dest = pushed;
        }
        if(payVector.dst2(dest.x, dest.y) < 0.001f * 0.001f){
            payVector.clamp(-half, -half, half, half);
            if(canMove){
                if(movePayload(payload)) payload = null;
            }else if(canDump){
                dumpPayload();
            }
        }
    }

    /** {@code dumpPayload()}: onto the ground, a little forward; back if it won't go. */
    public void dumpPayload(){
        // translate payload forward slightly
        float tx = Angles.trnsx(payload.rotation(), 0.1f), ty = Angles.trnsy(payload.rotation(), 0.1f);
        payload.set(payload.x() + tx, payload.y() + ty, payload.rotation());
        if(payload.dump()){
            payload = null;
        }else{
            payload.set(payload.x() - tx, payload.y() - ty, payload.rotation());
        }
    }

    /** {@code hasArrived()}: at the centre ({@code payVector.isZero(0.01f)}). */
    public boolean hasArrived(){
        return payVector.len2() < 0.01f;
    }

    /** {@code rotdeg()}. */
    public final float rotdeg(){
        return rotation * 90f;
    }

    /** {@code sense(content)}: 1 for the payload's unit type or block. */
    @Override
    public double sense(ContentType type, int id){
        if(payload != null && payload.content().type() == type && payload.content().id() == id) return 1;
        return super.sense(type, id);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PayloadBuild<T>)into;
        out.payload = payload == null ? null : (T)payload.copy(context);
        out.payVector = new Vec2(payVector);
        out.payRotation = payRotation;
        out.carried = carried;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(payload != null);
        if(payload != null) payload.hash(hasher);
        hasher.f(payVector.x).f(payVector.y).f(payRotation).bool(carried);
    }

    @Override
    public void write(CheckpointOut out){
        out.obj("payload", payload).obj("payVector", payVector).f("payRotation", payRotation).bool("carried", carried);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void read(CheckpointIn in){
        payload = (T)in.obj("payload", Payload.class);
        payVector = in.obj("payVector", Vec2.class);
        payRotation = in.f("payRotation");
        carried = in.bool("carried");
    }
}
