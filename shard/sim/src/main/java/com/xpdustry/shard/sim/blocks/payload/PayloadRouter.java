package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PayloadRouter} (PayloadRouter.java), the payload router and the reinforced one: a payload conveyor
 * that turns. A payload that matches its sorted block or unit type goes straight on, the way it came; any other turns
 * to the next side that will take it, skipping straight on when it sorts. A processor's {@code control config} turns it
 * by hand and holds it for 6 s.
 *
 * <p>State, by vanilla's names: {@code sorted} (a block or unit type, or null), {@code recDir} (the side the payload came
 * from, as its direction), {@code matches}, {@code controlTime}. Saved at revision 1: the sorted content and
 * {@code recDir}. {@code smoothRot} only draws.
 */
@Kind("payload-router")
public final class PayloadRouter extends PayloadConveyorBuild{
    static final Configs<PayloadRouter> CONFIGS = Configs.of(PayloadRouter.class)
        .on(ContentType.BLOCK, (router, block) -> router.sorted = ContentRef.block(block))
        .on(ContentType.UNIT, (router, unit) -> router.sorted = new ContentRef(ContentType.UNIT, unit))
        .clear(router -> router.sorted = null);

    /** The block or unit type it sorts, or null. */
    public ContentRef sorted;
    public int recDir;
    public boolean matches;
    public float controlTime = -1f;

    private boolean invert(){
        return block().baked() && block().fields().b("invert");
    }

    /**
     * {@code pickNext()}: with a payload and no hand control, a match goes straight on; anything else turns, side by
     * side, until a next block that isn't blocked takes it ({@code acceptPayload} from itself, which also takes dropped
     * payloads), at most four times.
     */
    public void pickNext(){
        if(item != null && controlTime <= 0f){
            if(matches){
                // when the item matches, always move forward
                rotation = recDir;
                onProximityUpdate();
            }else{
                int rotations = 0;
                do{
                    rotation = (rotation + 1) % 4;
                    // vanilla quirk: skipping recDir from 3 leaves the rotation at 4 until the next turn
                    if(!matches && sorted != null && rotation == recDir) rotation++;
                    onProximityUpdate();
                    // force update to transfer if necessary
                    if(next instanceof PayloadConveyorBuild && !(next instanceof PayloadRouter)) next.updateTile();
                }while((blocked || next == null || !next.acceptPayload(next, item)) && ++rotations < 4);
            }
        }else{
            onProximityUpdate();
        }
    }

    /** {@code control(config, p1)}: turns to {@code p1} and holds there for 6 s, as turrets do. */
    @Override
    public void control(LAccess type, double p1, double p2, double p3, double p4){
        super.control(type, p1, p2, p3, p4);
        if(type == LAccess.config){
            int prev = rotation;
            rotation = Mathf.mod((int)p1, 4);
            // when manually controlled, routers do not turn automatically for a while, same as turrets
            controlTime = 60f * 6f;
            if(prev != rotation) onProximityUpdate();
        }
    }

    /** {@code onControlSelect(unit)} (PayloadRouter.java:120): it faces on as it does; logic control snaps it back. */
    @Override
    public void onControlSelect(Unit unit){
        super.onControlSelect(unit);
        recDir = rotation;
        checkMatch();
    }

    @Override
    public void handlePayload(Building source, Payload payload){
        super.handlePayload(source, payload);
        // don't overwrite logic recDir
        if(controlTime < 0f) recDir = source == null ? rotation : source.relativeTo(this);
        checkMatch();
        pickNext();
    }

    /** {@code checkMatch()}. Vanilla quirk kept: {@code &&} binds before {@code ||}, which changes nothing here. */
    public void checkMatch(){
        matches = sorted != null && (item instanceof Payload.BuildPayload build && build.block().id() == sorted.id() && sorted.type() == ContentType.BLOCK)
            || (item instanceof Payload.UnitPayload unit && sorted != null && sorted.type() == ContentType.UNIT && unit.unit().type().id() == sorted.id());
        if(invert()) matches = !matches;
    }

    @Override
    public void moveFailed(){
        pickNext();
    }

    @Override
    public void updateTile(){
        super.updateTile();
        controlTime -= 1f;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return sorted;
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PayloadRouter)into;
        out.sorted = sorted;
        out.recDir = recDir;
        out.matches = matches;
        out.controlTime = controlTime;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(sorted == null ? -1 : sorted.type().ordinal()).i(sorted == null ? -1 : sorted.id()).i(recDir).bool(matches).f(controlTime);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.obj("sorted", sorted).i("recDir", recDir).bool("matches", matches).f("controlTime", controlTime);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        sorted = in.obj("sorted", ContentRef.class);
        recDir = in.i("recDir");
        matches = in.bool("matches");
        controlTime = in.f("controlTime");
    }
}
