package com.xpdustry.shard.sim.blocks.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code TileableLogicDisplay.TileableLogicDisplayBuild} (TileableLogicDisplay.java): tile displays that
 * join their touching neighbours into one display. Joining is vanilla's {@code linkDisplays}: on the turn after a
 * neighbour came or went, a depth-first walk over the proximity picks the root (the bottom-left-most member, as the
 * walk meets them) and the bounding box. A flush on any member counts on the root, which processors sense; the
 * width and height are the box's, less the frame. The save is {@link LogicDisplay}'s: the transform.
 *
 * <p>The walk follows the proximity, whose order is first-seen edge order in Shard and identity-hash order in vanilla
 * (see {@code Buildings}), so the two can pick different roots for a shape that isn't a rectangle.
 */
@Kind("tileable-logic-display")
public final class TileableLogicDisplay extends Building implements Checkpointable, LogicDrawable{
    /** {@code operations}: flushes counted while this was a root. */
    public long operations;
    public float[] transform;
    /** {@code rootDisplay}: the member flushes count on. */
    public TileableLogicDisplay rootDisplay = this;
    public int tilesWidth = 1, tilesHeight = 1, originX, originY;
    /** Which of the 8 neighbours are the same block and team (drawing only, but kept as vanilla). */
    public int bits;
    public boolean needsUpdate, rectangular;

    private int frameSize(){
        return block().baked() ? block().fields().i("frameSize") : 6;
    }

    @Override
    public double sense(LAccess sensor){
        return switch(sensor){
            // accounts for the display frame
            case displayWidth -> tilesWidth * 32f - frameSize() * 2;
            case displayHeight -> tilesHeight * 32f - frameSize() * 2;
            case bufferSize -> 0;
            case operations -> rootDisplay.operations;
            default -> super.sense(sensor);
        };
    }

    @Override
    public boolean drawable(Processor exec){
        return isValid() && (exec.privileged || (team == exec.team() && !block().privileged()));
    }

    @Override
    public void drawFlush(){
        rootDisplay.operations++;
    }

    @Override
    public void updateTile(){
        if(needsUpdate){
            needsUpdate = false;
            linkDisplays(this);
        }
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        bits = 0;
        TileGrid tiles = world().tiles();
        for(int i = 0; i < 8; i++){
            Point2 d = Geometry.d8(i);
            int x = tileX() + d.x(), y = tileY() + d.y();
            if(!tiles.inBounds(x, y)) continue;
            int pos = tiles.pos(x, y);
            if(tiles.block(pos) == block().id() && tiles.team(pos) == team) bits |= 1 << i;
        }
    }

    @Override
    public void onProximityAdded(){
        super.onProximityAdded();
        needsUpdate = true;
        updateOthers();
    }

    @Override
    public void onProximityRemoved(){
        super.onProximityRemoved();
        for(int i = 0; i < proximitySize(); i++){
            if(proximity(i) instanceof TileableLogicDisplay other) other.needsUpdate = true;
        }
        updateOthers();
    }

    /** {@code updateOthers}: the diagonal neighbours of the same block and team update their proximity. */
    private void updateOthers(){
        TileGrid tiles = world().tiles();
        for(int i = 0; i < 4; i++){
            Point2 d = Geometry.d8edge(i);
            int x = tileX() + d.x(), y = tileY() + d.y();
            if(!tiles.inBounds(x, y)) continue;
            int pos = tiles.pos(x, y);
            if(tiles.block(pos) == block().id() && tiles.team(pos) == team){
                Building other = world().buildings().at(pos);
                if(other != null) other.onProximityUpdate();
            }
        }
    }

    /** {@code linkDisplays(start)} (TileableLogicDisplay.java:35). */
    static void linkDisplays(TileableLogicDisplay start){
        TileableLogicDisplay root = null;
        int topX = start.tileX(), topY = start.tileY(), botX = start.tileX(), botY = start.tileY();
        var queue = new ArrayList<TileableLogicDisplay>();
        var displays = new ArrayList<TileableLogicDisplay>();
        var processed = new IntSet();
        queue.add(start);
        displays.add(start);
        while(!queue.isEmpty()){
            var next = queue.removeLast();
            processed.add(next.id());
            // the root is the bottom leftmost position
            if(root == null || next.tileX() < root.tileX() || next.tileY() < root.tileY()) root = next;
            topX = Math.max(next.tileX(), topX);
            topY = Math.max(next.tileY(), topY);
            botX = Math.min(next.tileX(), botX);
            botY = Math.min(next.tileY(), botY);
            for(int i = 0; i < next.proximitySize(); i++){
                if(next.proximity(i) instanceof TileableLogicDisplay display && processed.add(display.id())){
                    queue.add(display);
                    displays.add(display);
                }
            }
        }
        int width = topX - botX + 1, height = topY - botY + 1;
        boolean rectangular = width * height == displays.size();
        for(var member : displays){
            member.needsUpdate = false;
            member.rectangular = rectangular;
            member.rootDisplay = root;
            member.tilesWidth = width;
            member.tilesHeight = height;
            member.originX = botX;
            member.originY = botY;
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.l("operations", operations).floats("transform", transform).ref("rootDisplay", rootDisplay)
            .i("tilesWidth", tilesWidth).i("tilesHeight", tilesHeight).i("originX", originX).i("originY", originY)
            .i("bits", bits).bool("needsUpdate", needsUpdate).bool("rectangular", rectangular);
    }

    @Override
    public void read(CheckpointIn in){
        operations = in.l("operations");
        transform = in.floats("transform");
        rootDisplay = in.ref("rootDisplay", TileableLogicDisplay.class);
        tilesWidth = in.i("tilesWidth");
        tilesHeight = in.i("tilesHeight");
        originX = in.i("originX");
        originY = in.i("originY");
        bits = in.i("bits");
        needsUpdate = in.bool("needsUpdate");
        rectangular = in.bool("rectangular");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (TileableLogicDisplay)into;
        out.operations = operations;
        out.transform = transform == null ? null : transform.clone();
        out.rootDisplay = context.map(rootDisplay);
        out.tilesWidth = tilesWidth;
        out.tilesHeight = tilesHeight;
        out.originX = originX;
        out.originY = originY;
        out.bits = bits;
        out.needsUpdate = needsUpdate;
        out.rectangular = rectangular;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.l(operations).bool(transform != null);
        if(transform != null) hasher.floats(transform);
        hasher.i(rootDisplay.id()).i(tilesWidth).i(tilesHeight).i(originX).i(originY).i(bits).bool(needsUpdate).bool(rectangular);
    }
}
