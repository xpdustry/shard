package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code BeamNode.BeamNodeBuild} (BeamNode.java:156): the beam node and beam tower. Whenever a tile changed
 * in the world since its last update ({@link Buildings#tileChanges()}), it casts a beam in each of the four directions
 * up to {@code range} tiles past its edge, stopping at an insulated building, and links to the first powered building
 * of its team that isn't a laser node. A beam that moves away unlinks and reflows both sides.
 *
 * <p>Vanilla doesn't save the beams: a loaded node recasts them on its first update ({@link #lastChange} -2). A
 * checkpoint keeps them, since the next recast compares against them.
 */
@Kind("beam-node")
public final class BeamNode extends Building implements Checkpointable{
    /** The building each beam reaches, by direction ({@code Geometry.d4}); null for none. */
    public final Building[] links = new Building[4];
    /** The tile each beam reaches, by direction; -1 for none. */
    public final int[] dests = {-1, -1, -1, -1};
    /** {@link Buildings#tileChanges()} at the last recast. */
    public int lastChange = -2;
    @Derived("the block's range, read on first use")
    private int beamRange = -1;

    /** The block's {@code range}: how many tiles past its edge a beam reaches. */
    private int beamRange(){
        if(beamRange == -1) beamRange = block().baked() ? block().fields().i("range") : 5;
        return beamRange;
    }

    @Override
    public void updateTile(){
        int changes = world().buildings().tileChanges();
        if(lastChange != changes){
            lastChange = changes;
            updateDirections();
        }
    }

    /** Charged and gaining, carried by its batteries, or neither, by its graph's balance. */
    @Override
    public BlockStatus status(double stateTick){
        float balance = power.graph.getPowerBalance();
        if(balance > 0f) return BlockStatus.active;
        if(balance < 0f && power.graph.getLastPowerStored() > 0) return BlockStatus.noOutput;
        return BlockStatus.noInput;
    }

    /** {@code pickedUp()}: a payload carrier took it; its beams go without unlinking (vanilla). */
    public void pickedUp(){
        Arrays.fill(links, null);
        Arrays.fill(dests, -1);
    }

    /** {@code updateDirections()}: recasts the four beams. */
    public void updateDirections(){
        World world = world();
        Buildings buildings = world.buildings();
        int x = tileX(), y = tileY(), range = beamRange();
        for(int i = 0; i < 4; i++){
            Building prev = links[i];
            int dx = Geometry.d4x(i), dy = Geometry.d4y(i);
            links[i] = null;
            dests[i] = -1;
            int offset = block().size() / 2;
            for(int j = 1 + offset; j <= range + offset; j++){
                Building other = buildings.at(x + j * dx, y + j * dy);
                if(other != null && other.isInsulated()) break;
                if(other != null && other.block().hasPower() && other.info().connectedPower && other.team == team && !LaserNode.isNode(other.block())){
                    links[i] = other;
                    dests[i] = world.tiles().pos(x + j * dx, y + j * dy);
                    break;
                }
            }

            Building next = links[i];
            if(next != prev){
                // vanilla quirk: a building that never joined the update list (a battery) is never "added", so a beam
                // leaving it keeps both links
                if(prev != null && prev.isAdded()){
                    prev.power.links.removeValue(pos());
                    power.links.removeValue(prev.pos());
                    PowerGraph newgraph = PowerGraph.create(world);
                    newgraph.reflow(this);
                    if(prev.power.graph != newgraph){
                        PowerGraph og = PowerGraph.create(world);
                        og.reflow(prev);
                    }
                }
                if(next != null){
                    power.links.addUnique(next.pos());
                    next.power.links.addUnique(pos());
                    power.graph.addGraph(next.power.graph);
                }
            }
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("links", links, 4).ints("dests", dests).i("lastChange", lastChange);
    }

    @Override
    public void read(CheckpointIn in){
        Building[] stored = in.refs("links", Building.class);
        int[] tiles = in.ints("dests");
        if(stored == null || stored.length != 4 || tiles == null || tiles.length != 4) throw new CheckpointException("a beam node has 4 beams");
        System.arraycopy(stored, 0, links, 0, 4);
        System.arraycopy(tiles, 0, dests, 0, 4);
        lastChange = in.i("lastChange");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (BeamNode)into;
        for(int i = 0; i < 4; i++) out.links[i] = context.map(links[i]);
        System.arraycopy(dests, 0, out.dests, 0, 4);
        out.lastChange = lastChange;
        out.beamRange = beamRange;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        for(Building link : links) hasher.i(link == null ? -1 : link.id());
        hasher.ints(dests, 0, 4).i(lastChange);
    }
}
