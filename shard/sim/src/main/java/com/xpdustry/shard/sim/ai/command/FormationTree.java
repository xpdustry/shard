package com.xpdustry.shard.sim.ai.command;

import com.xpdustry.shard.common.struct.*;

/**
 * Arc's {@code IntQuadTree} (arc/math/geom/IntQuadTree.java) over a formation's circles, for {@link UnitGroup}: object
 * {@code i}'s hitbox is the square of side {@code sizes[i]} centred on {@code positions[i * 2], positions[i * 2 + 1]},
 * read when asked, so moving a circle moves its hitbox. Order is Arc's, which the formation's result depends on:
 * children are visited top-left, top-right, bottom-left, bottom-right, then the node's own objects, which live in an
 * unordered list (a removal moves the last object into the hole).
 *
 * <p>Arc's quirk kept: an object whose hitbox misses the root's bounds isn't inserted at all. The root spans
 * {@code (0, 0)} to the world's size while formation offsets are centred on 0, so only circles reaching into the
 * positive quadrant collide ({@code UnitGroup.calculateFormation}).
 */
final class FormationTree{
    private static final int MAX_OBJECTS = 5;

    private final float x, y, width, height;
    private final float[] positions;
    private final float[] sizes;
    private final IntSeq objects = new IntSeq(false, 10);
    private FormationTree botLeft, botRight, topLeft, topRight;
    private boolean leaf = true;
    private int totalObjects;
    // the hitbox of the last object read, as Arc's tmp rect
    private float hx, hy, hw, hh;

    FormationTree(float x, float y, float width, float height, float[] positions, float[] sizes){
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.positions = positions;
        this.sizes = sizes;
    }

    private void hitbox(int index){
        float size = sizes[index];
        hx = positions[index * 2] - size / 2f;
        hy = positions[index * 2 + 1] - size / 2f;
        hw = size;
        hh = size;
    }

    private boolean overlaps(float rx, float ry, float rw, float rh){
        return x < rx + rw && x + width > rx && y < ry + rh && y + height > ry;
    }

    private void split(){
        if(!leaf) return;
        float subW = width / 2, subH = height / 2;
        if(botLeft == null){
            botLeft = new FormationTree(x, y, subW, subH, positions, sizes);
            botRight = new FormationTree(x + subW, y, subW, subH, positions, sizes);
            topLeft = new FormationTree(x, y + subH, subW, subH, positions, sizes);
            topRight = new FormationTree(x + subW, y + subH, subW, subH, positions, sizes);
        }
        leaf = false;
        // transfer objects to children if they fit entirely in one
        for(int i = 0; i < objects.size; i++){
            int obj = objects.items[i];
            hitbox(obj);
            FormationTree child = fittingChild(hx, hy, hw, hh);
            if(child != null){
                child.insert(obj);
                objects.removeIndex(i);
                i--;
            }
        }
    }

    void insert(int obj){
        hitbox(obj);
        // an object not in the tree is ignored
        if(!overlaps(hx, hy, hw, hh)) return;
        totalObjects++;
        if(leaf && objects.size + 1 > MAX_OBJECTS) split();
        if(leaf){
            objects.add(obj);
        }else{
            hitbox(obj);
            FormationTree child = fittingChild(hx, hy, hw, hh);
            if(child != null) child.insert(obj);
            else objects.add(obj);
        }
    }

    void clear(){
        objects.clear();
        totalObjects = 0;
        if(!leaf){
            topLeft.clear();
            topRight.clear();
            botLeft.clear();
            botRight.clear();
        }
        leaf = true;
    }

    private FormationTree fittingChild(float bx, float by, float bw, float bh){
        float verticalMidpoint = x + (width / 2), horizontalMidpoint = y + (height / 2);
        boolean topQuadrant = by > horizontalMidpoint;
        boolean bottomQuadrant = by < horizontalMidpoint && (by + bh) < horizontalMidpoint;
        if(bx < verticalMidpoint && bx + bw < verticalMidpoint){
            if(topQuadrant) return topLeft;
            else if(bottomQuadrant) return botLeft;
        }else if(bx > verticalMidpoint){
            if(topQuadrant) return topRight;
            else if(bottomQuadrant) return botRight;
        }
        return null;
    }

    /** {@code intersect(x, y, width, height, out)}: every object whose hitbox overlaps the rectangle, in Arc's order. */
    void intersect(float rx, float ry, float rw, float rh, IntSeq out){
        if(!leaf){
            if(topLeft.overlaps(rx, ry, rw, rh)) topLeft.intersect(rx, ry, rw, rh, out);
            if(topRight.overlaps(rx, ry, rw, rh)) topRight.intersect(rx, ry, rw, rh, out);
            if(botLeft.overlaps(rx, ry, rw, rh)) botLeft.intersect(rx, ry, rw, rh, out);
            if(botRight.overlaps(rx, ry, rw, rh)) botRight.intersect(rx, ry, rw, rh, out);
        }
        for(int i = 0; i < objects.size; i++){
            int item = objects.items[i];
            hitbox(item);
            if(hx < rx + rw && hx + hw > rx && hy < ry + rh && hy + hh > ry) out.add(item);
        }
    }
}
