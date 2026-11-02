package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.sim.unit.*;

import java.util.*;
import java.util.function.*;

/**
 * A team's unit quadtree, {@code TeamData.unitTree}, node for node: Arc's {@code QuadTree} filled by {@code insert} in
 * {@code Groups.unit} order ({@code Teams.updateTeamStats}). Its traversal order is what {@code radar} breaks ties by and
 * what {@code query unit} lists, which the spatial grids don't keep ({@code SpatialIndex}).
 *
 * <p>Placement uses each unit's hitbox when it was inserted ({@link LogicUnits} records them at {@code TEAMS}); the
 * queries test the current hitboxes, as Arc calls {@code hitbox()} again on every test. As in Arc, a node splits when
 * a sixth object arrives until it is {@value #MIN_NODE_SIZE} units wide, an object that straddles a split line stays in
 * the parent, and the objects a split moves out leave by swap-removal.
 */
final class UnitTree{
    /** {@code QuadTree.minNodeSize}. */
    static final float MIN_NODE_SIZE = 10f;
    /** {@code QuadTree.maxObjectsPerNode}. */
    static final int MAX_OBJECTS = 5;

    private final float x, y, width, height;
    private boolean leaf = true;
    private UnitTree botLeft, botRight, topLeft, topRight;
    private Unit[] objects = new Unit[MAX_OBJECTS];
    /** Each object's hitbox when it was inserted: x, y and size. */
    private float[] boxes = new float[MAX_OBJECTS * 3];
    private int count;

    UnitTree(float x, float y, float width, float height){
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** {@code insert(obj)}, with the unit's hitbox at (bx, by), {@code size} wide. */
    void insert(Unit unit, float bx, float by, float size){
        if(!overlaps(x, y, width, height, bx, by, size, size)) return;
        if(leaf && count + 1 > MAX_OBJECTS) split();
        if(leaf){
            add(unit, bx, by, size);
        }else{
            UnitTree child = fittingChild(bx, by, size);
            if(child != null) child.insert(unit, bx, by, size);
            else add(unit, bx, by, size);
        }
    }

    private void add(Unit unit, float bx, float by, float size){
        if(count == objects.length){
            objects = Arrays.copyOf(objects, count * 2);
            boxes = Arrays.copyOf(boxes, count * 6);
        }
        objects[count] = unit;
        boxes[count * 3] = bx;
        boxes[count * 3 + 1] = by;
        boxes[count * 3 + 2] = size;
        count++;
    }

    private void split(){
        if(!leaf || width <= MIN_NODE_SIZE || height <= MIN_NODE_SIZE) return;
        float subW = width / 2, subH = height / 2;
        botLeft = new UnitTree(x, y, subW, subH);
        botRight = new UnitTree(x + subW, y, subW, subH);
        topLeft = new UnitTree(x, y + subH, subW, subH);
        topRight = new UnitTree(x + subW, y + subH, subW, subH);
        leaf = false;
        // transfer objects to children if they fit entirely in one
        for(int i = 0; i < count; i++){
            float bx = boxes[i * 3], by = boxes[i * 3 + 1], size = boxes[i * 3 + 2];
            UnitTree child = fittingChild(bx, by, size);
            if(child != null){
                child.insert(objects[i], bx, by, size);
                count--;
                objects[i] = objects[count];
                System.arraycopy(boxes, count * 3, boxes, i * 3, 3);
                objects[count] = null;
                i--;
            }
        }
    }

    /** {@code getFittingChild(rect)}: the quadrant the box fits in whole, or null. */
    private UnitTree fittingChild(float bx, float by, float size){
        float verticalMidpoint = x + (width / 2), horizontalMidpoint = y + (height / 2);
        boolean topQuadrant = by > horizontalMidpoint;
        boolean bottomQuadrant = by < horizontalMidpoint && (by + size) < horizontalMidpoint;
        if(bx < verticalMidpoint && bx + size < verticalMidpoint){
            if(topQuadrant) return topLeft;
            if(bottomQuadrant) return botLeft;
        }else if(bx > verticalMidpoint){
            if(topQuadrant) return topRight;
            if(bottomQuadrant) return botRight;
        }
        return null;
    }

    /** {@code intersect(x, y, w, h, cons)}: children top left, top right, bottom left, bottom right, then this node's own. */
    void intersect(float qx, float qy, float qw, float qh, Consumer<Unit> out){
        if(!leaf){
            if(overlaps(topLeft.x, topLeft.y, topLeft.width, topLeft.height, qx, qy, qw, qh)) topLeft.intersect(qx, qy, qw, qh, out);
            if(overlaps(topRight.x, topRight.y, topRight.width, topRight.height, qx, qy, qw, qh)) topRight.intersect(qx, qy, qw, qh, out);
            if(overlaps(botLeft.x, botLeft.y, botLeft.width, botLeft.height, qx, qy, qw, qh)) botLeft.intersect(qx, qy, qw, qh, out);
            if(overlaps(botRight.x, botRight.y, botRight.width, botRight.height, qx, qy, qw, qh)) botRight.intersect(qx, qy, qw, qh, out);
        }
        for(int i = 0; i < count; i++){
            Unit unit = objects[i];
            float size = unit.hitSize;
            if(overlaps(unit.x - size / 2f, unit.y - size / 2f, size, size, qx, qy, qw, qh)) out.accept(unit);
        }
    }

    /** {@code Rect.overlaps}: strictly. */
    static boolean overlaps(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh){
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }
}
