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
 * Vanilla's {@code PayloadConveyor.PayloadConveyorBuild} (PayloadConveyor.java:77), the payload conveyor and the base of
 * the payload router ({@link PayloadConveyor}, {@link PayloadRouter}). Its clock is the world's ({@code Time.time}):
 * every conveyor steps on the same frames, every {@code moveTime} ticks, and at each step hands its payload to the
 * aligned block in front ({@link #next}), which it updates first, or dumps it onto the ground.
 *
 * <p>State, by vanilla's names: {@code item}, {@code progress}, {@code itemRotation}, {@code animation},
 * {@code curInterp}, {@code lastInterp}, {@code next}, {@code blocked}, {@code step}, {@code stepAccepted}. Only
 * {@code progress}, {@code itemRotation} and the item are saved; a load starts the others afresh and recomputes
 * {@code next} from proximity.
 *
 * <p>A unit entering it ({@link #onControlSelect}) becomes its item where it is, with a new entity id posted to the next
 * frame ({@link Payloads#handleUnitPayload}). Not ported: drawing.
 */
public abstract class PayloadConveyorBuild extends Building implements Checkpointable{
    public Payload item;
    public float progress, itemRotation, animation;
    public float curInterp, lastInterp;
    public Building next;
    public boolean blocked;
    public int step = -1, stepAccepted = -1;

    @Derived("moveTime, read from the block on first use")
    private float moveTime = Float.NaN;
    @Derived("moveForce, read from the block on first use")
    private float moveForce;
    @Derived("payloadLimit, read from the block on first use")
    private float payloadLimit;
    @Derived("pushUnits, read from the block on first use")
    private boolean pushUnits;

    private void params(){
        if(!Float.isNaN(moveTime)) return;
        BlockDef block = block();
        if(block.baked()){
            String interp = block.fields().s("interp");
            if(!"interp:pow5".equals(interp)) throw new UnsupportedOperationException(block.name() + "'s interp " + interp + " isn't ported");
            moveTime = block.fields().f("moveTime");
            moveForce = block.fields().f("moveForce");
            payloadLimit = block.fields().f("payloadLimit");
            pushUnits = block.fields().b("pushUnits");
        }else{
            moveTime = 45f;
            moveForce = 201f;
            payloadLimit = 3f;
            pushUnits = true;
        }
    }

    public float moveTime(){
        params();
        return moveTime;
    }

    public float payloadLimit(){
        params();
        return payloadLimit;
    }

    @Override
    public boolean canControlSelect(Unit unit){
        int on = unit.tileOn();
        return item == null && unit.type().allowedInPayloads() && !unit.spawnedByCore && unit.hitSize / Footprint.TILE_SIZE <= payloadLimit()
            && on != -1 && world().buildings().at(on) == this;
    }

    /** {@code onControlSelect(unit)} (PayloadConveyor.java:90): the unit becomes the item, nothing else changes. */
    @Override
    public void onControlSelect(Unit unit){
        item = Payloads.handleUnitPayload(unit);
    }

    @Override
    public Payload takePayload(){
        Payload t = item;
        item = null;
        return t;
    }

    /** {@code onProximityUpdate()}: the next block is the one in front, aligned and of the same size, or larger and overlapping. */
    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        int size = block().size(), dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation);
        Building accept = nearby(dx * (size / 2 + 1), dy * (size / 2 + 1));
        // next block must be aligned and of the same size
        int other = accept == null ? 0 : accept.block().size();
        if(accept != null && (
            // same size
            (other == size && tileX() + dx * size == accept.tileX() && tileY() + dy * size == accept.tileY())
            // differing sizes
            || (other > size && (rotation % 2 == 0
                ? Math.abs(accept.y() - y()) <= (other * Footprint.TILE_SIZE - size * Footprint.TILE_SIZE) / 2f
                : Math.abs(accept.x() - x()) <= (other * Footprint.TILE_SIZE - size * Footprint.TILE_SIZE) / 2f)))){
            next = accept;
        }else{
            next = null;
        }
        checkBlocked();
    }

    /** {@code checkBlocked()}: a solid tile in front that moves no payloads, or a next block that faces back. */
    void checkBlocked(){
        int ntrns = 1 + block().size() / 2;
        TileGrid tiles = world().tiles();
        int nx = tileX() + Geometry.d4x(rotation) * ntrns, ny = tileY() + Geometry.d4y(rotation) * ntrns;
        boolean solidAhead = false;
        if(tiles.inBounds(nx, ny)){
            int pos = tiles.pos(nx, ny);
            BlockDef ahead = world().content().block(tiles.block(pos));
            solidAhead = tiles.solid(pos) && !(ahead.outputsPayload() || ahead.acceptsPayload());
        }
        blocked = solidAhead || (next != null && next.payloadCheck(rotation));
    }

    @Override
    public Payload getPayload(){
        return item;
    }

    @Override
    public void updateTile(){
        if(item != null){
            item.update(null, this);
            if(item.isDead()) item = null;
        }
        if(enabled){
            lastInterp = curInterp;
            curInterp = fract();
            // rollover skip
            if(lastInterp > curInterp) lastInterp = 0f;
            progress = time() % moveTime();
        }
        updatePayload();
        if(!enabled) return;
        if(item != null && next == null) PayloadBlock.pushOutput(world(), item, progress / moveTime());
        // TODO in vanilla: "nondeterministic input priority"
        int curStep = curStep();
        if(curStep > step){
            boolean valid = step != -1;
            step = curStep;
            boolean had = item != null;
            if(valid && stepAccepted != curStep && item != null){
                checkBlocked();
                if(next != null){
                    // trigger update forward
                    next.updateTile();
                    if(next != null && next.acceptPayload(this, item)){
                        // move forward
                        next.handlePayload(this, item);
                        item = null;
                        moved();
                    }
                }else if(!blocked){
                    // dump item forward
                    if(item.dump()){
                        item = null;
                        moved();
                        // NetServer.syncBuilding(this)
                        world().publish(new PayloadBlockEvent.Synced(tile()));
                    }
                }
            }
            if(had && item != null) moveFailed();
        }
    }

    /** {@code moveFailed()}: a step came and the item stayed. */
    public void moveFailed(){
    }

    /** {@code moved()}: the item left at a step. */
    public void moved(){
    }

    @Override
    public void onDestroyed(){
        if(item != null) item.destroyed();
        super.onDestroyed();
    }

    /** {@code time()}: the world's clock, vanilla's {@code Time.time}. */
    public float time(){
        return world().timers().time();
    }

    /** {@code unitOn(unit)}: a unit on the belt moves with it, by how far the belt moved since the last tick. */
    @Override
    public void unitOn(Unit unit){
        params();
        if(!pushUnits || !enabled || lastInterp == 0f) return;
        // calculate derivative of units moved last frame
        float delta = (curInterp - lastInterp) * block().size() * Footprint.TILE_SIZE;
        var v = new Vec2().trns(rotdeg(), delta * moveForce).scl(1f / Math.max(unit.mass(), 201f));
        unit.move(v.x, v.y);
    }

    @Override
    public boolean acceptPayload(Building source, Payload payload){
        return item == null && payload.fits(payloadLimit()) && (source == this || enabled && progress <= 5f);
    }

    @Override
    public void handlePayload(Building source, Payload payload){
        item = payload;
        stepAccepted = curStep();
        itemRotation = source == this ? rotdeg() : Angles.angle(source.x(), source.y(), x(), y());
        animation = 0;
        updatePayload();
    }

    @Override
    public double sense(ContentType type, int id){
        if(item != null && item.content().type() == type && item.content().id() == id) return 1;
        return super.sense(type, id);
    }

    @Override
    public void onRemoved(){
        super.onRemoved();
        if(item != null) item.dump();
    }

    /** {@code updatePayload()}: the item follows the belt's eased progress, turning from where it came in. */
    public void updatePayload(){
        if(item == null) return;
        if(animation > fract()) animation = Mathf.lerp(animation, 0.8f, 0.15f);
        animation = Math.max(animation, fract());
        float fract = animation;
        float rot = Mathf.slerp(itemRotation, rotdeg(), fract);
        float span = Footprint.TILE_SIZE * block().size();
        var v = fract < 0.5f ? new Vec2().trns(itemRotation + 180, (0.5f - fract) * span) : new Vec2().trns(rotdeg(), (fract - 0.5f) * span);
        item.set(x() + v.x, y() + v.y, rot);
    }

    public int curStep(){
        return (int)(time() / moveTime());
    }

    /** {@code fract()}: {@code interp.apply(progress / moveTime)}, pow5 for every v160.7 conveyor. */
    public float fract(){
        return Interp.pow5.apply(progress / moveTime());
    }

    /** {@code rotdeg()}. */
    public final float rotdeg(){
        return rotation * 90f;
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PayloadConveyorBuild)into;
        out.item = item == null ? null : item.copy(context);
        out.progress = progress;
        out.itemRotation = itemRotation;
        out.animation = animation;
        out.curInterp = curInterp;
        out.lastInterp = lastInterp;
        out.next = context.map(next);
        out.blocked = blocked;
        out.step = step;
        out.stepAccepted = stepAccepted;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(item != null);
        if(item != null) item.hash(hasher);
        hasher.f(progress).f(itemRotation).f(animation).f(curInterp).f(lastInterp).i(next == null ? -1 : next.id())
            .bool(blocked).i(step).i(stepAccepted);
    }

    @Override
    public void write(CheckpointOut out){
        out.obj("item", item).f("progress", progress).f("itemRotation", itemRotation).f("animation", animation)
            .f("curInterp", curInterp).f("lastInterp", lastInterp).ref("next", next).bool("blocked", blocked)
            .i("step", step).i("stepAccepted", stepAccepted);
    }

    @Override
    public void read(CheckpointIn in){
        item = in.obj("item", Payload.class);
        progress = in.f("progress");
        itemRotation = in.f("itemRotation");
        animation = in.f("animation");
        curInterp = in.f("curInterp");
        lastInterp = in.f("lastInterp");
        next = in.ref("next", Building.class);
        blocked = in.bool("blocked");
        step = in.i("step");
        stepAccepted = in.i("stepAccepted");
    }
}
