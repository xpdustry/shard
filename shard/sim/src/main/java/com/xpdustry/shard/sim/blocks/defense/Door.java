package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Door.DoorBuild} (Door.java): a wall that is solid to units while closed. A player opens or closes it
 * with a {@code Boolean} config (the client's tap sends it); a processor with {@code control enabled}, at most once
 * every 80 ticks by its chain's first door's timer. Touching doors form a chain that opens and closes together; a
 * closing door skips itself while a ground unit stands in it. Its building is a plain {@code Building}, not a
 * {@code WallBuild}: bullets hit it as any building.
 *
 * <p>{@link #chain} is vanilla's {@code chained}: the doors reachable through proximity when it was last rebuilt, in
 * breadth-first order, one {@link DoorChain} every door in the chain holds, as vanilla's doors hold one {@code Seq}.
 * Vanilla rebuilds it whenever a door's proximity is rebuilt: when it is placed, and when a neighbour goes
 * ({@code Tile.changeBuild} rebuilds the neighbours'), so a removed door leaves its chain. The chain's first door's
 * timer gates the processors.
 */
@Kind("door")
public final class Door extends Building implements Checkpointable{
    static final Configs<Door> CONFIGS = Configs.of(Door.class).on(Boolean.class, Door::toggle);

    public boolean open;
    /** The chain, this door included; null until its proximity is first built. */
    public DoorChain chain;
    @Derived("the block's timerToggle, read on first use")
    private int timerToggle = -1;

    /** The config handler (Door.java:35); sounds and effects only play on clients. */
    private static void toggle(Door base, boolean open){
        Door[] doors = base.chain == null ? new Door[]{base} : base.chain.doors;
        for(Door entity : doors){
            // skip doors with things in them
            if((anyEntities(entity) && !open) || entity.open == open) continue;
            entity.open = open;
            // recache and pathfinder.updateTile(entity.tile): the grid's solidity follows
            entity.world().buildings().solidityChanged(entity);
        }
    }

    @Override
    public void onProximityAdded(){
        super.onProximityAdded();
        updateChained();
    }

    @Override
    public void onProximityRemoved(){
        super.onProximityRemoved();
        for(int i = 0; i < proximitySize(); i++){
            if(proximity(i) instanceof Door d) d.updateChained();
        }
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.enabled) return open ? 1 : 0;
        return super.sense(sensor);
    }

    /** {@code control(enabled, …)}: a config from the server, as vanilla's never sets {@link #enabled}. */
    @Override
    public void control(LAccess type, double p1, double p2, double p3, double p4){
        if(type == LAccess.enabled){
            boolean shouldOpen = !Mathf.zero(p1);
            if(open == shouldOpen || (anyEntities(this) && !shouldOpen) || !origin().timer(timerToggle(), 80f)) return;
            configureAny(shouldOpen);
        }
    }

    /** {@code origin()}: the chain's first door, whose timer the chain shares. */
    public Door origin(){
        return chain == null ? this : chain.doors[0];
    }

    /** The chain's doors, this one included; empty until its proximity is first built. */
    public Door[] chained(){
        return chain == null ? DoorChain.NONE : chain.doors;
    }

    /** {@code updateChained()}: a breadth-first walk of the doors in proximity, from this one. */
    public void updateChained(){
        // vanilla tells the doors it reached by the new Seq they point at
        var marker = new DoorChain();
        var reached = new ArrayList<Door>();
        var queue = new ArrayDeque<Door>();
        chain = marker;
        queue.addLast(this);
        while(!queue.isEmpty()){
            Door next = queue.removeLast();
            reached.add(next);
            for(int i = 0; i < next.proximitySize(); i++){
                if(next.proximity(i) instanceof Door d && d.chain != marker){
                    d.chain = marker;
                    queue.addFirst(d);
                }
            }
        }
        marker.doors = reached.toArray(DoorChain.NONE);
    }

    @Override
    public boolean checkSolid(){
        return !open;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return open;
    }

    private int timerToggle(){
        if(timerToggle == -1) timerToggle = block().baked() ? block().fields().i("timerToggle") : 1;
        return timerToggle;
    }

    /**
     * {@code Units.anyEntities(door.tile)}: a ground unit's tile hitbox over the square of the block at the door's tile.
     * For a door no longer on its tile, that is whatever stands there now.
     */
    static boolean anyEntities(Building door){
        SpatialIndex units = SpatialIndex.of(door.world());
        if(units == null) return false;
        TileGrid tiles = door.world().tiles();
        int pos = door.tile();
        int size = door.world().content().block(tiles.block(pos)).size();
        float side = size * Footprint.TILE_SIZE;
        float x = tiles.x(pos) * Footprint.TILE_SIZE + Footprint.worldOffset(size), y = tiles.y(pos) * Footprint.TILE_SIZE + Footprint.worldOffset(size);
        return units.anyEntities(x - side / 2f, y - side / 2f, side, side, true);
    }

    @Override
    public void write(CheckpointOut out){
        out.bool("open", open).ref("chain", chain);
    }

    @Override
    public void read(CheckpointIn in){
        open = in.bool("open");
        chain = in.ref("chain", DoorChain.class);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Door)into;
        out.open = open;
        out.chain = chain == null ? null : context.map(chain);
        out.timerToggle = timerToggle;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        Door[] doors = chained();
        hasher.bool(open).i(doors.length);
        if(doors.length == 0) return;
        Door origin = doors[0];
        hasher.i(origin.id());
        // the chain's doors once, at its origin: every other door of it holds the same chain
        if(origin == this || origin.chain != chain) for(Door door : doors) hasher.i(door.id());
    }
}
