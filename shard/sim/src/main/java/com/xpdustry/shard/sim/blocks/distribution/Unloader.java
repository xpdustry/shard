package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Unloader.UnloaderBuild} (Unloader.java:79). Every {@code speed} ticks it moves one item from the
 * neighbour that has the most of it to the one that has the least and takes it, among the neighbours it can trade
 * with ({@link #possibleBlocks}, rebuilt on each proximity update). Unconfigured, it rotates through every item
 * from the last one it moved ({@link #rotations}). Ties go to the neighbour used longest ago: each trade zeroes the
 * two {@link ContainerStat#lastUsed} counters, and every update ages them all. Cores and containers only give, and
 * get priority to.
 *
 * <p>Disabled by logic, an unloader doesn't update. The stats come from the world's {@link ContainerStats} pool,
 * whose reused stats keep their {@code lastUsed}, as vanilla's do.
 *
 * <p>A container linked to a core ({@link Storage#linkedCore}) unloads only as its core does: when the unloader may
 * unload cores, and the rules allow core unloaders ({@link Building#canUnload()}). As vanilla, the partial check is
 * made on the unloader's proximity update, so a link made later counts from the next one. Vanilla's
 * {@code dumpingFrom} and {@code dumpingTo} are locals here: only the draw reads them outside a trade.
 */
@Kind("unloader")
public final class Unloader extends Building implements Checkpointable{
    static final Configs<Unloader> CONFIGS = Configs.of(Unloader.class)
        .on(ContentType.ITEM, (unloader, item) -> unloader.sortItem = item)
        .clear(unloader -> unloader.sortItem = -1);
    private static final KindKey CORE = KindKey.of("core-block"), STORAGE = KindKey.of("storage-block");

    /** Vanilla's comparator (Unloader.java:85): last are taken from, first are given to. */
    private static final Comparator<ContainerStat> COMPARATOR = (x, y) -> {
        // priority to core and core containers always
        int unloadCore = Boolean.compare(!x.notStorage, !y.notStorage);
        if(unloadCore != 0) return unloadCore;
        // priority to receive if it cannot give
        int unloadPriority = Boolean.compare(x.canUnload && !x.canLoad, y.canUnload && !y.canLoad);
        if(unloadPriority != 0) return unloadPriority;
        // priority to give if it cannot receive
        int loadPriority = Boolean.compare(x.canUnload || !x.canLoad, y.canUnload || !y.canLoad);
        if(loadPriority != 0) return loadPriority;
        int loadFactor = Float.compare(x.loadFactor, y.loadFactor);
        if(loadFactor != 0) return loadFactor;
        // inverted
        return Integer.compare(y.lastUsed, x.lastUsed);
    };

    public float unloadTimer;
    /** The id of the last item moved: where an unconfigured unloader's search starts. */
    public int rotations;
    /** The item it unloads, or -1 for any. */
    public int sortItem = -1;
    /** The neighbours it can trade with, sorted at each trade. */
    public ArrayList<ContainerStat> possibleBlocks = new ArrayList<>();
    @Derived("the block's parameters, read on first use")
    private float speed = Float.NaN;
    @Derived("the block's allowCoreUnload, read on first use")
    private byte allowCoreUnload = -1;

    private float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 1f;
        return speed;
    }

    private boolean allowCoreUnload(){
        if(allowCoreUnload == -1) allowCoreUnload = (byte)(!block().baked() || block().fields().b("allowCoreUnload") ? 1 : 0);
        return allowCoreUnload == 1;
    }

    /** {@code isPossibleItem(item)}: whether some neighbour can give it and another take it; sets their flags. */
    private boolean isPossibleItem(int item){
        boolean hasProvider = false, hasReceiver = false, isDistinct = false;
        for(int i = 0, l = possibleBlocks.size(); i < l; i++){
            ContainerStat pb = possibleBlocks.get(i);
            Building other = pb.building;
            // set the stats of buildings in possibleBlocks while we are at it
            pb.canLoad = pb.notStorage && other.acceptItem(this, item);
            pb.canUnload = other.canUnload() && other.items != null && other.items.has(item);
            // that's also handling framerate issues and slow conveyor belts, to avoid skipping items if nulloader
            isDistinct |= (hasProvider && pb.canLoad) || (hasReceiver && pb.canUnload);
            hasProvider |= pb.canUnload;
            hasReceiver |= pb.canLoad;
        }
        return isDistinct;
    }

    @Override
    public void onProximityUpdate(){
        // filter all blocks in the proximity that will never be able to trade items
        super.onProximityUpdate();
        ContainerStats pool = ContainerStats.of(world());
        if(pool != null) pool.freeAll(possibleBlocks);
        possibleBlocks.clear();
        for(int i = 0; i < proximitySize(); i++){
            Building other = proximity(i);
            // avoid blocks of the wrong team (Teams.canInteract)
            if(!(other.team == team || other.team == com.xpdustry.shard.sim.rules.VanillaIds.DERELICT)) continue;
            KindKey kind = other.block().buildKind();
            // partial check
            boolean storage = kind.equals(STORAGE);
            boolean canLoad = !(kind.equals(CORE) || storage);
            boolean canUnload = other.canUnload() && (allowCoreUnload() || canLoad || (other instanceof Storage s && s.linkedCore == null))
                && other.items != null;
            // avoid blocks that can neither give nor receive items
            if(canLoad || canUnload){
                ContainerStat pb = pool == null ? new ContainerStat() : pool.obtain();
                pb.building = other;
                pb.notStorage = canLoad;
                possibleBlocks.add(pb);
            }
        }
    }

    @Override
    public void updateTile(){
        if(((unloadTimer += delta()) < speed()) || possibleBlocks.size() < 2) return;
        int item = -1;
        if(sortItem != -1){
            if(isPossibleItem(sortItem)) item = sortItem;
        }else{
            // selects the next item for nulloaders
            int l = world().content().items();
            for(int i = 0; i < l; i++){
                int id = (rotations + i + 1) % l;
                if(isPossibleItem(id)){
                    item = id;
                    break;
                }
            }
        }
        if(item != -1){
            // next rotation for nulloaders
            rotations = item;
            for(ContainerStat pb : possibleBlocks){
                Building other = pb.building;
                int maxAccepted = other.getMaximumAccepted(item);
                pb.loadFactor = maxAccepted == 0 || other.items == null ? 0 : other.items.get(item) / (float)maxAccepted;
                // increment the priority if not used
                pb.lastUsed = (pb.lastUsed + 1) % Integer.MAX_VALUE;
            }
            // Seq.sort: Arc's TimSort, stable, as List.sort
            possibleBlocks.sort(COMPARATOR);
            unloadAccumulate(item);
        }else{
            unloadTimer = Math.min(unloadTimer, speed());
        }
    }

    /** {@code unloadAccumulate(item)}: trades while the timer allows, so the rate doesn't depend on the frame rate. */
    public void unloadAccumulate(int item){
        if(item == -1) return;
        boolean any = false;
        int pbs = possibleBlocks.size();
        while(unloadTimer >= speed()){
            ContainerStat dumpingTo = null, dumpingFrom = null;
            // choose the building to accept the item
            for(int i = 0; i < pbs; i++){
                ContainerStat pb = possibleBlocks.get(i);
                if(pb.canLoad && pb.building.acceptItem(this, item)){
                    dumpingTo = pb;
                    break;
                }
            }
            // choose the building to take the item from
            for(int i = pbs - 1; i >= 0; i--){
                ContainerStat pb = possibleBlocks.get(i);
                if(pb.canUnload && pb.building.canUnload() && pb.building.items != null && pb.building.items.has(item)){
                    dumpingFrom = pb;
                    break;
                }
            }
            if(dumpingFrom == null || dumpingTo == null) break;
            Building from = dumpingFrom.building, to = dumpingTo.building;
            int fromMax = from.getMaximumAccepted(item), toMax = to.getMaximumAccepted(item);
            dumpingFrom.loadFactor = fromMax == 0 || from.items == null ? 0f : from.items.get(item) / (float)fromMax;
            dumpingTo.loadFactor = toMax == 0 || to.items == null ? 0f : to.items.get(item) / (float)toMax;
            // trade the items
            if(dumpingFrom.loadFactor != dumpingTo.loadFactor || !dumpingFrom.canLoad){
                to.handleItem(this, item);
                from.removeStack(item, 1);
                dumpingTo.lastUsed = dumpingFrom.lastUsed = 0;
                unloadTimer -= speed();
                any = true;
            }else{
                break;
            }
        }
        if(!any) unloadTimer = Math.min(unloadTimer, speed());
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return sortItem == -1 ? null : ContentRef.item(sortItem);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("unloadTimer", unloadTimer).i("rotations", rotations).i("sortItem", sortItem).list("possibleBlocks", possibleBlocks);
    }

    @Override
    public void read(CheckpointIn in){
        unloadTimer = in.f("unloadTimer");
        rotations = in.i("rotations");
        sortItem = in.i("sortItem");
        possibleBlocks = new ArrayList<>(in.list("possibleBlocks", ContainerStat.class));
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Unloader)into;
        out.unloadTimer = unloadTimer;
        out.rotations = rotations;
        out.sortItem = sortItem;
        out.possibleBlocks = new ArrayList<>(possibleBlocks.size());
        for(ContainerStat stat : possibleBlocks) out.possibleBlocks.add(stat.copy(context));
        out.speed = speed;
        out.allowCoreUnload = allowCoreUnload;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(unloadTimer).i(rotations).i(sortItem).i(possibleBlocks.size());
        for(ContainerStat stat : possibleBlocks) stat.hash(hasher);
    }
}
