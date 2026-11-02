package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code StackConveyor.StackConveyorBuild} (StackConveyor.java:114): the plastanium and surge conveyors. A
 * line moves whole stacks: a loading end ({@link #STATE_LOAD}) fills up from behind and the sides, hands the full
 * stack to the next stack conveyor, which hands it on once its {@link #cooldown} has reeled it in, until an unloading
 * end ({@link #STATE_UNLOAD}) dumps it (plastanium) or moves it forward (surge, {@code outputRouter} false). The state
 * follows the neighbours on every proximity update, and a change updates the neighbours in turn. {@link #link} is the
 * packed tile the stack came from, -1 when empty.
 *
 * <p>Disabled by logic, a stack conveyor still reels its stack in at full speed but hands nothing on.
 *
 * <p>Not ported: {@code dropped}
 * (payloads), and drawing state ({@code blendprox} is computed only off headless).
 */
@Kind("stack-conveyor")
public final class StackConveyor extends Building implements Checkpointable{
    public static final int STATE_MOVE = 0, STATE_LOAD = 1, STATE_UNLOAD = 2;

    public int state;
    /** The packed tile ({@code Point2.pack}) the stack came from, or -1: empty. */
    public int link = -1;
    public float cooldown;
    /** The item being moved, or -1. */
    public int lastItem = -1;
    @Derived("true only inside onProximityUpdate")
    boolean proxUpdating;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** The block's fields this kind reads. */
    private record Params(float speed, float recharge, float baseEfficiency, boolean outputRouter){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(0f, 2f, 0f, true);
            var fields = block.fields();
            return new Params(fields.f("speed"), fields.f("recharge"), fields.f("baseEfficiency"), fields.b("outputRouter"));
        }
    }

    private Params params(){
        if(params == null) params = Params.of(block());
        return params;
    }

    /** StackConveyor.java:71, for this building's tile and current state. */
    boolean blends(int otherx, int othery, int otherrot, BlockDef otherblock){
        if(state == STATE_LOAD){
            // standard conveyor mode
            return Blending.outputsItems(otherblock) && Blending.lookingAtEither(this, otherx, othery, otherrot, otherblock);
        }else if(state == STATE_UNLOAD && !params().outputRouter){
            // router mode
            Building other = world().buildings().at(otherx, othery);
            boolean noSideBlend = otherblock.baked() && otherblock.fields().b("noSideBlend");
            return otherblock.acceptsItems()
                && (!noSideBlend || Blending.lookingAtEither(this, otherx, othery, otherrot, otherblock))
                && (Blending.notLookingAt(this, otherx, othery, otherrot, otherblock)
                    || (isStackConveyor(otherblock) && Blending.facing(otherx, othery, otherrot, tileX(), tileY())))
                && !(other instanceof StackConveyor s && s.state == STATE_UNLOAD)
                && !(other instanceof StackConveyor s2 && s2.state == STATE_MOVE && !Blending.facing(otherx, othery, otherrot, tileX(), tileY()));
        }
        return Blending.outputsItems(otherblock) && Blending.blendsArmored(this, otherx, othery, otherrot, otherblock, null)
            && isStackConveyor(otherblock);
    }

    private static boolean isStackConveyor(BlockDef block){
        return block.baked() && block.isA(KindKey.of("stack-conveyor"));
    }

    /** {@code overwrote(builds)} (StackConveyor.java:319): built over a conveyor, it takes the conveyor's first item as a stack. */
    @Override
    public void overwrote(java.util.List<Building> builds){
        if(builds.getFirst() instanceof ConveyorBuild build){
            int item = build.items.first();
            if(item != -1) handleStack(item, build.items.get(item), -1);
        }
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        int lastState = state;
        state = STATE_MOVE;
        Blending.Rule rule = this::blends;
        int bits = Blending.blendBits(this, rule);
        boolean outputRouter = params().outputRouter;
        // a 0 that faces into a conveyor with none behind it
        if(bits == 0 && Blending.blends(this, 0, rule) && (!Blending.blends(this, 2, rule) || back() instanceof StackConveyor b && b.state == STATE_UNLOAD)) state = STATE_LOAD;
        // a 0 that faces into none with a conveyor behind it; the rule reads the state just set
        if(outputRouter && bits == 0 && !Blending.blends(this, 0, rule) && Blending.blends(this, 2, rule)) state = STATE_UNLOAD;
        if(!outputRouter && !(front() instanceof StackConveyor)) state = STATE_UNLOAD;
        // cannot load when facing
        if(state == STATE_LOAD){
            for(int i = 0; i < proximitySize(); i++){
                Building near = proximity(i);
                if(near instanceof StackConveyor && near.front() == this){
                    state = STATE_MOVE;
                    break;
                }
            }
        }
        // update other conveyor state when this conveyor's state changes
        if(state != lastState){
            proxUpdating = true;
            for(int i = 0; i < proximitySize(); i++){
                Building near = proximity(i);
                if(!(near instanceof StackConveyor b && b.proxUpdating && b.state != STATE_UNLOAD)) near.onProximityUpdate();
            }
            proxUpdating = false;
        }
    }

    @Override
    public boolean canUnload(){
        return state != STATE_LOAD;
    }

    @Override
    public void updateTile(){
        Params params = params();
        // the item still needs to be "reeled" in when disabled
        float eff = enabled ? (efficiency + params.baseEfficiency) : 1f;
        // reel in crater
        if(cooldown > 0f) cooldown = Mathf.clamp(cooldown - params.speed * eff * delta(), 0f, params.recharge);
        // indicates empty state
        if(link == -1) return;
        // crater needs to be centered
        if(cooldown > 0f) return;
        // get current item
        if(lastItem == -1 || !items.has(lastItem)) lastItem = items.first();
        // do not continue if disabled, will still allow one to be reeled in to prevent visual stacking
        if(!enabled) return;
        if(state == STATE_UNLOAD){
            // Vanilla quirk: the condition is (lastItem != null && !outputRouter) ? moveForward : dump, so a router end
            // with no item dumps any
            while(lastItem != -1 && !params.outputRouter ? moveForward(lastItem) : dump(lastItem)){
                if(!params.outputRouter) items.remove(lastItem, 1);
                if(!items.has(lastItem)){
                    poofOut();
                    lastItem = -1;
                    break;
                }
            }
        }else{
            // transfer
            if(state != STATE_LOAD || items.total() >= getMaximumAccepted(lastItem)){
                if(front() instanceof StackConveyor e && e.team == team && e.link == -1){
                    e.items.add(items);
                    e.lastItem = lastItem;
                    e.link = pos();
                    link = -1;
                    items.clear();
                    cooldown = params.recharge;
                    e.cooldown = 1;
                }
            }
        }
    }

    /** {@code poofIn()}: starts a stack here; the effect only draws. */
    void poofIn(){
        link = pos();
    }

    /** {@code poofOut()}: the stack is gone; the effect only draws. */
    void poofOut(){
        link = -1;
    }

    @Override
    public int acceptStack(int item, int amount, int sourceTeam){
        if(items.any() && !items.has(item)) return 0;
        return super.acceptStack(item, amount, sourceTeam);
    }

    @Override
    public void handleItem(Building source, int item){
        if(items.empty()) poofIn();
        super.handleItem(source, item);
        lastItem = item;
    }

    @Override
    public void handleStack(int item, int amount, int sourceTeam){
        if(amount <= 0) return;
        if(items.empty()) poofIn();
        super.handleStack(item, amount, sourceTeam);
        lastItem = item;
    }

    @Override
    public int removeStack(int item, int amount){
        int removed = super.removeStack(item, amount);
        if(items.empty()) poofOut();
        return removed;
    }

    @Override
    public void itemTaken(int item){
        if(items.empty()) poofOut();
    }

    @Override
    public boolean acceptItem(Building source, int item){
        // player threw items
        if(this == source) return items.total() < info().itemCapacity && (!items.any() || items.has(item));
        // still cooling down
        if(cooldown > params().recharge - 1f) return false;
        return !(state != STATE_LOAD
            || (items.any() && !items.has(item))
            || items.total() >= getMaximumAccepted(item)
            || front() == source);
    }

    /** {@code read}'s last line: the item being moved is the first one held. */
    public void loaded(){
        lastItem = items.first();
    }

    /** {@link #link} as tile coordinates, for tests and tools. */
    public Point2 linkTile(){
        return link == -1 ? null : Point2.unpack(link);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("state", state).i("link", link).f("cooldown", cooldown).i("lastItem", lastItem);
    }

    @Override
    public void read(CheckpointIn in){
        state = in.i("state");
        link = in.i("link");
        cooldown = in.f("cooldown");
        lastItem = in.i("lastItem");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (StackConveyor)into;
        out.state = state;
        out.link = link;
        out.cooldown = cooldown;
        out.lastItem = lastItem;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(state).i(link).f(cooldown).i(lastItem);
    }

    /** {@code dropped()}: placed again by a payload unit, a loaded stack links back to whatever is behind it. */
    @Override
    public void dropped(){
        super.dropped();
        if(items.any()){
            link = Point2.pack(tileX() + Geometry.d4x(rotation + 2), tileY() + Geometry.d4y(rotation + 2));
            cooldown = 0f;
        }else{
            link = -1;
        }
    }
}
