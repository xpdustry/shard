package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code CargoAI} (CargoAI.java), the manifold's: shuttles between its cargo loader ({@link Unit#tether})
 * and its team's unload points ({@link UnitCargoUnloadPoint}). Empty, it waits by the loader; when the loader holds
 * items it picks, on the retarget timer, the item it holds most of that some point asks for, and the first point for
 * it that isn't stale, starting from {@link #targetIndex} ({@link #findAnyTarget}), and takes a stack. Loaded, it
 * flies to that point and every {@code dropSpacing} ticks puts in what fits; once empty, or after
 * {@code emptyWaitTime} of trying, it moves on to the next point for the item ({@link #findDropTarget}), and drops its
 * stack when no point wants it. A point reconfigured or picked up on the way is given up.
 *
 * <p>Points are found in {@code indexer.getFlagged}'s order ({@link Buildings#flagged}). Vanilla quirk, kept: the
 * targets are never checked for being alive, so a destroyed point is flown to until the next retarget.
 */
public class CargoAi extends AiController{
    public static final String KIND = "cargo-ai";
    public static final float EMPTY_WAIT_TIME = 60f * 2f, DROP_SPACING = 60f * 1.5f;
    public static final float TRANSFER_RANGE = 20f, MOVE_RANGE = 6f, MOVE_SMOOTHING = 20f;

    /** {@code unloadTarget}: the point it carries to, or null. */
    public UnitCargoUnloadPoint unloadTarget;
    /** {@code itemTarget}: the item it carries there, or -1. */
    public int itemTarget = -1;
    public float noDestTimer;
    public int targetIndex;

    @Derived("a scratch list, empty between calls: vanilla's static targets")
    private final List<UnitCargoUnloadPoint> targets = new ArrayList<>();

    public CargoAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public CargoAi(){
    }

    protected CargoAi(CargoAi source, CopyContext context){
        super(source, context);
        unloadTarget = context.map(source.unloadTarget);
        itemTarget = source.itemTarget;
        noDestTimer = source.noDestTimer;
        targetIndex = source.targetIndex;
    }

    @Override
    public void updateMovement(){
        if(!unit.params().tethered() || unit.tether == null) return;

        Building build = unit.tether;

        if(build.items == null) return;

        // empty, approach the loader, even if there's nothing to pick up (units hanging around doing nothing looks bad)
        if(!unit.hasItem()){
            moveTo(build.x(), build.y(), MOVE_RANGE, MOVE_SMOOTHING);

            // check if ready to pick up
            if(build.items.any() && unit.within(build.x(), build.y(), TRANSFER_RANGE)){
                if(retarget()){
                    findAnyTarget(build);

                    // target has been found, grab items and go
                    if(unloadTarget != null){
                        ItemTransfers.takeItems(unit.world(), build, itemTarget, Math.min(unit.itemCapacity(), build.items.get(itemTarget)), unit);
                    }
                }
            }
        }else{ // the unit has an item, deposit it somewhere

            // there may be no current target, try to find one
            if(unloadTarget == null){
                if(retarget()){
                    findDropTarget(unit.item(), 0, null);

                    // if there is not even a single place to unload, dump items
                    if(unloadTarget == null) unit.clearItem();
                }
            }else{
                // what if some prankster reconfigures or picks up the target while the unit is moving? we can't have that!
                if(unloadTarget.item != itemTarget || unloadTarget.isPayload()){
                    unloadTarget = null;
                    return;
                }

                moveTo(unloadTarget.x(), unloadTarget.y(), MOVE_RANGE, MOVE_SMOOTHING);

                // deposit in bursts, unloading can take a while
                if(unit.within(unloadTarget.x(), unloadTarget.y(), TRANSFER_RANGE) && timerGet(TIMER_TARGET2, DROP_SPACING)){
                    int max = unloadTarget.acceptStack(unit.item(), unit.stackAmount, unit.team);

                    // deposit items when it's possible
                    if(max > 0){
                        ItemTransfers.transferItemTo(unit.world(), unit, unit.item(), max, unit.x, unit.y, unloadTarget);
                    }

                    // keep the target for at most emptyWaitTime, then try the next one in case others need it
                    if(!unit.hasItem() || (noDestTimer += DROP_SPACING) >= EMPTY_WAIT_TIME){
                        // next targeting attempt will try the next destination point
                        targetIndex = findDropTarget(unit.item(), targetIndex, unloadTarget);
                        noDestTimer = 0f;

                        // nothing found at all, clear item
                        if(unloadTarget == null) unit.clearItem();
                    }
                }
            }
        }
    }

    /** {@code findDropTarget(item, offset, ignore)}: the next point for {@code item} after {@code offset} that isn't stale. */
    public int findDropTarget(int item, int offset, UnitCargoUnloadPoint ignore){
        unloadTarget = null;
        itemTarget = item;

        select(item);

        if(targets.isEmpty()) return 0;

        // search from offset + 1
        for(int i = 0; i < targets.size(); i++){
            int index = (i + offset + 1) % targets.size();
            UnitCargoUnloadPoint target = targets.get(index);

            if(!target.stale){
                unloadTarget = target;
                targets.clear();
                return index;
            }
        }

        // a stale target is better than nothing
        unloadTarget = targets.getFirst();
        targets.clear();
        return 0;
    }

    /** {@code findAnyTarget(build)}: a point for the item the loader holds most of, trying the next items in turn. */
    public void findAnyTarget(Building build){
        unloadTarget = null;
        itemTarget = -1;

        List<Building> baseTargets = unit.world().buildings().flagged(unit.team, BlockFlag.unitCargoUnloadPoint);

        if(baseTargets.isEmpty()) return;

        // orderedItems, sorted by most items in descending order (a stable sort), and try each one
        int items = unit.world().content().items();
        int[] ordered = new int[items];
        int count = 0;
        for(int item = 0; item < items; item++){
            if(build.items.get(item) > 0) ordered[count++] = item;
        }
        for(int i = 1; i < count; i++){
            int item = ordered[i], amount = build.items.get(item), j = i - 1;
            while(j >= 0 && build.items.get(ordered[j]) < amount){
                ordered[j + 1] = ordered[j];
                j--;
            }
            ordered[j + 1] = item;
        }

        UnitCargoUnloadPoint lastStale = null;

        outer:
        for(int o = 0; o < count; o++){
            int item = ordered[o];
            select(baseTargets, item);

            if(!targets.isEmpty()) itemTarget = item;

            for(int i = 0; i < targets.size(); i++){
                UnitCargoUnloadPoint target = targets.get((i + targetIndex) % targets.size());

                lastStale = target;

                if(!target.stale){
                    unloadTarget = target;
                    break outer;
                }
            }
        }

        // if the only thing that was found was a "stale" target, at least try that...
        if(unloadTarget == null && lastStale != null) unloadTarget = lastStale;

        targets.clear();
    }

    /** {@code targets.selectFrom(getFlagged(team, unitCargoUnloadPoint), u -> u.item == item)}. */
    private void select(int item){
        select(unit.world().buildings().flagged(unit.team, BlockFlag.unitCargoUnloadPoint), item);
    }

    private void select(List<Building> from, int item){
        targets.clear();
        for(int i = 0; i < from.size(); i++){
            if(from.get(i) instanceof UnitCargoUnloadPoint point && point.item == item) targets.add(point);
        }
    }

    // state

    @Override
    public UnitController copy(CopyContext context){
        return new CargoAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.i(unloadTarget == null ? -1 : unloadTarget.id()).i(itemTarget).f(noDestTimer).i(targetIndex);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.ref("unloadTarget", unloadTarget).i("itemTarget", itemTarget).f("noDestTimer", noDestTimer).i("targetIndex", targetIndex);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        unloadTarget = in.ref("unloadTarget", UnitCargoUnloadPoint.class);
        itemTarget = in.i("itemTarget");
        noDestTimer = in.f("noDestTimer");
        targetIndex = in.i("targetIndex");
    }
}
