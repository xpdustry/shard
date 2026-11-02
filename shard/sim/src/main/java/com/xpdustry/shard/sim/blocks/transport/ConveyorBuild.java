package com.xpdustry.shard.sim.blocks.transport;

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
 * Vanilla's {@code Conveyor.ConveyorBuild} (Conveyor.java:123): up to three items on a belt, each at a position across
 * ({@link #xs}, -1 to 1) and along ({@link #ys}, 0 to 1), in order from back to front. Every tick each item moves
 * {@code speed * edelta()} forward, keeping {@code itemSpace} behind the one ahead and, when the next conveyor is
 * aligned, behind that conveyor's last item; at the front it passes to {@link #next}. Items enter at the back from
 * behind, or at the middle from the sides. An empty belt sleeps.
 *
 * <p>The two kinds are the conveyor (basic and titanium) and the armored conveyor, which takes items from the sides
 * only from conveyors. Vanilla's {@code instanceof ConveyorBuild} checks are this class.
 *
 * <p>A processor stops a belt through {@code control enabled}: a disabled conveyor still updates
 * ({@code noUpdateDisabled} is false) but has no efficiency, so nothing moves, and starts again on the tick it is
 * enabled. Loading a save runs one {@link #updateTile()} ({@link #loaded()}, Conveyor.java:432).
 *
 * <p>Not ported: logic sensing ({@code sense}, {@code senseObject}, {@code setProp}; WP67 has no sensor surface yet)
 * and drawing state.
 */
public abstract sealed class ConveyorBuild extends Building implements Checkpointable permits Conveyor, ArmoredConveyor{
    static final float ITEM_SPACE = 0.4f;
    static final int CAPACITY = 3;

    /** Per slot: the item id, or -1. */
    public int[] ids = {-1, -1, -1};
    public float[] xs = new float[CAPACITY], ys = new float[CAPACITY];
    /** How many slots hold an item. */
    public int len;
    /** The building in front, as of the last proximity update, or null. */
    public Building next;
    /** {@link #next} if it was a same-team conveyor then, else null. */
    public ConveyorBuild nextc;
    /** Whether {@link #nextc} had this conveyor's rotation then. */
    public boolean aligned;
    /** Vanilla never sets it: the slot an aligned conveyor writes the passed item's x into is always 0. */
    public int lastInserted;
    /** The slot a side input goes in, from the last update. */
    public int mid;
    /** The lowest item position after the last update, 1 when empty. */
    public float minitem = 1f;
    /** {@code blendbits}: the blend type from the last proximity update (1: a curve, which clogs sooner). */
    public int blendbits;
    public float clogHeat;
    @Derived("the block's speed, read on first use")
    private float speed = Float.NaN;

    /** {@code updateTile}'s first call, at read time: loaded items have moved once, with no neighbours yet. */
    public final void loaded(){
        updateTile();
    }

    /**
     * {@code overwrote(builds)} (Conveyor.java:192): a conveyor built over a conveyor takes over its items where they
     * lay, its clog heat and its insertion state.
     */
    @Override
    public void overwrote(List<Building> builds){
        if(builds.getFirst() instanceof ConveyorBuild build){
            ids = build.ids.clone();
            xs = build.xs.clone();
            ys = build.ys.clone();
            len = build.len;
            clogHeat = build.clogHeat;
            lastInserted = build.lastInserted;
            mid = build.mid;
            minitem = build.minitem;
            items.add(build.items);
        }
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        blendbits = Blending.blendBits(this, this::blends);
        next = front();
        nextc = next instanceof ConveyorBuild conveyor && next.team == team ? conveyor : null;
        aligned = nextc != null && rotation == next.rotation;
    }

    /** The kind's {@code blends(tile, rotation, otherx, othery, otherrot, otherblock)}. */
    abstract boolean blends(int otherx, int othery, int otherrot, BlockDef otherblock);

    @Override
    public void unitOn(Unit unit){
        if(!pushUnits() || clogHeat > 0.5f || !enabled) return;
        noSleep();
        float mspeed = speed() * Footprint.TILE_SIZE * 55f;
        float centerSpeed = 0.1f;
        float centerDstScl = 3f;
        float tx = Geometry.d4x(rotation), ty = Geometry.d4y(rotation);
        float centerx = 0f, centery = 0f;
        float x = x(), y = y();
        if(Math.abs(tx) > Math.abs(ty)){
            centery = Mathf.clamp((y - unit.y) / centerDstScl, -centerSpeed, centerSpeed);
            if(Math.abs(y - unit.y) < 1f) centery = 0f;
        }else{
            centerx = Mathf.clamp((x - unit.x) / centerDstScl, -centerSpeed, centerSpeed);
            if(Math.abs(x - unit.x) < 1f) centerx = 0f;
        }
        if(len * ITEM_SPACE < 0.9f) unit.impulse((tx * mspeed + centerx) * delta(), (ty * mspeed + centery) * delta());
    }

    @Override
    public void updateTile(){
        minitem = 1f;
        mid = 0;
        // skip updates if possible
        if(len == 0 && Mathf.equal(timeScale, 1f)){
            clogHeat = 0f;
            sleep();
            return;
        }
        float nextMax = aligned ? 1f - Math.max(ITEM_SPACE - nextc.minitem, 0) : 1f;
        float moved = speed() * edelta();
        for(int i = len - 1; i >= 0; i--){
            float nextpos = (i == len - 1 ? 100f : ys[i + 1]) - ITEM_SPACE;
            float maxmove = Mathf.clamp(nextpos - ys[i], 0, moved);
            ys[i] += maxmove;
            if(ys[i] > nextMax) ys[i] = nextMax;
            if(ys[i] > 0.5 && i > 0) mid = i - 1;
            xs[i] = Mathf.approach(xs[i], 0, moved * 2);
            if(ys[i] >= 1f && pass(ids[i])){
                // align X position if passing forwards
                if(aligned) nextc.xs[nextc.lastInserted] = xs[i];
                // Vanilla quirk: removes len - i of the passed item, more than one if items behind it reached the end too
                items.remove(ids[i], len - i);
                len = Math.min(i, len);
            }else if(ys[i] < minitem){
                minitem = ys[i];
            }
        }
        if(minitem < ITEM_SPACE + (blendbits == 1 ? 0.3f : 0f)){
            clogHeat = Mathf.approachDelta(clogHeat, 1f, 1f / 60f);
        }else{
            clogHeat = 0f;
        }
        noSleep();
    }

    /** {@code pass(item)}: hands the item to a same-team {@link #next} that takes it. */
    public boolean pass(int item){
        if(item != -1 && next != null && next.team == team && next.acceptItem(this, item)){
            next.handleItem(this, item);
            return true;
        }
        return false;
    }

    @Override
    public int removeStack(int item, int amount){
        noSleep();
        int removed = 0;
        for(int j = 0; j < amount; j++){
            for(int i = 0; i < len; i++){
                if(ids[i] == item){
                    remove(i);
                    removed++;
                    break;
                }
            }
        }
        items.remove(item, removed);
        return removed;
    }

    /** {@code sensor @progress}: how far along the front item is (Conveyor.java:436). */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return len == 0 ? 0 : ys[len - 1];
        return super.sense(sensor);
    }

    /** {@code sensor @firstItem}: the front item, not the module's first (Conveyor.java:445). */
    @Override
    public Object senseObject(LAccess sensor){
        if(sensor == LAccess.firstItem && len > 0) return ContentRef.item(ids[len - 1]);
        return super.senseObject(sensor);
    }

    /**
     * {@code setprop @item}: up to the conveyor's capacity, put on its back as a stack or taken off from its back
     * (Conveyor.java:451); a stack ignores whether the belt has room for it, as vanilla's.
     */
    @Override
    public void setProp(ContentType type, int id, double value){
        if(type == ContentType.ITEM && items != null){
            int amount = Math.min((int)value, CAPACITY);
            if(items.get(id) != amount){
                if(items.get(id) < amount) handleStack(id, amount - items.get(id), -1);
                else if(amount >= 0) removeStack(id, items.get(id) - amount);
            }
        }else super.setProp(type, id, value);
    }

    /** Vanilla quirk: any team's units and buildings may put a stack on a conveyor. */
    @Override
    public int acceptStack(int item, int amount, int sourceTeam){
        return Math.min((int)(minitem / ITEM_SPACE), amount);
    }

    @Override
    public void handleStack(int item, int amount, int sourceTeam){
        amount = Math.min(amount, CAPACITY - len);
        for(int i = amount - 1; i >= 0; i--){
            add(0);
            xs[0] = 0;
            ys[0] = i * ITEM_SPACE;
            ids[0] = item;
            items.add(item, 1);
        }
        noSleep();
    }

    @Override
    public boolean acceptItem(Building source, int item){
        if(len >= CAPACITY) return false;
        int direction = Math.abs(Blending.relativeTo(world(), Blending.facingEdge(source, this), tileX(), tileY()) - rotation);
        return ((direction == 0 && minitem >= ITEM_SPACE) || (direction % 2 == 1 && minitem > 0.7f)) && !(source.block().rotate() && next == source);
    }

    @Override
    public void handleItem(Building source, int item){
        if(len >= CAPACITY) return;
        int r = rotation;
        int relative = Blending.relativeTo(world(), Blending.facingEdge(source, this), tileX(), tileY());
        int ang = relative - r;
        float x = (ang == -1 || ang == 3) ? 1 : (ang == 1 || ang == -3) ? -1 : 0;
        noSleep();
        items.add(item, 1);
        if(Math.abs(relative - r) == 0){
            add(0);
            xs[0] = x;
            ys[0] = 0;
            ids[0] = item;
        }else{
            add(mid);
            xs[mid] = x;
            ys[mid] = 0.5f;
            ids[mid] = item;
        }
    }

    /** {@code add(o)}: shifts slots {@code o..len-1} up one. */
    public final void add(int o){
        for(int i = Math.max(o + 1, len); i > o; i--){
            ids[i] = ids[i - 1];
            xs[i] = xs[i - 1];
            ys[i] = ys[i - 1];
        }
        len++;
    }

    /** {@code remove(o)}: shifts slots above {@code o} down one. */
    public final void remove(int o){
        for(int i = o; i < len - 1; i++){
            ids[i] = ids[i + 1];
            xs[i] = xs[i + 1];
            ys[i] = ys[i + 1];
        }
        len--;
    }

    /** {@code next()} of {@code ChainedBuilding}: the conveyor in front. */
    public final ConveyorBuild chained(){
        return nextc;
    }

    /** The block's {@code speed}: how far an item moves per tick at full efficiency. */
    public final float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 0.03f;
        return speed;
    }

    private boolean pushUnits(){
        return !block().baked() || block().fields().b("pushUnits");
    }

    @Override
    public void write(CheckpointOut out){
        out.ints("ids", ids).floats("xs", xs).floats("ys", ys).i("len", len).ref("next", next).ref("nextc", nextc)
            .bool("aligned", aligned).i("lastInserted", lastInserted).i("mid", mid).f("minitem", minitem)
            .i("blendbits", blendbits).f("clogHeat", clogHeat);
    }

    @Override
    public void read(CheckpointIn in){
        in.ints("ids", ids);
        in.floats("xs", xs);
        in.floats("ys", ys);
        len = in.i("len");
        next = in.ref("next", Building.class);
        nextc = in.ref("nextc", ConveyorBuild.class);
        aligned = in.bool("aligned");
        lastInserted = in.i("lastInserted");
        mid = in.i("mid");
        minitem = in.f("minitem");
        blendbits = in.i("blendbits");
        clogHeat = in.f("clogHeat");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ConveyorBuild)into;
        out.ids = ids.clone();
        out.xs = xs.clone();
        out.ys = ys.clone();
        out.len = len;
        out.next = next == null ? null : context.map(next);
        out.nextc = nextc == null ? null : context.map(nextc);
        out.aligned = aligned;
        out.lastInserted = lastInserted;
        out.mid = mid;
        out.minitem = minitem;
        out.blendbits = blendbits;
        out.clogHeat = clogHeat;
        out.speed = speed;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.ints(ids).floats(xs).floats(ys).i(len).i(next == null ? -1 : next.id()).i(nextc == null ? -1 : nextc.id())
            .bool(aligned).i(lastInserted).i(mid).f(minitem).i(blendbits).f(clogHeat);
    }

    /** The slots in use, for tests and tools: {@code [item@x,y, ...]} back to front. */
    @Override
    public String toString(){
        var out = new ArrayList<String>(len);
        for(int i = 0; i < len; i++) out.add(ids[i] + "@" + xs[i] + "," + ys[i]);
        return super.toString() + "[" + String.join(", ", out) + "]";
    }
}
