package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.unit.*;

import java.util.*;

/**
 * A team's unit quadtree ({@code TeamData.unitTree}) as {@code Teams.updateTeamStats} builds it: Arc's {@code QuadTree},
 * cleared, then {@link #insert}ed one unit at a time in {@code Groups.unit} order (Teams.java:217), float for float.
 * Entries are indices into the {@link UnitGrid}'s members.
 *
 * <p>Insertion is not {@code fill} ({@link BodyTree}, {@code BulletTree}): a leaf splits when its sixth entry comes, and
 * the split moves the entries that fit a child into it, each removal swapping the node's last entry into the hole
 * ({@code Seq(false).remove}, QuadTree.java:58). So a node's own entries are not in insertion order, and a query sees
 * them in the order vanilla's does.
 *
 * <p>Placement uses each unit's hitbox at the fill; queries test the current one, as {@code intersect} calls
 * {@code hitbox()} again. A query visits each node's children top left, top right, bottom left, bottom right, then the
 * node's own entries.
 */
final class UnitTree{
    /** {@code QuadTree.minNodeSize}. */
    static final float MIN_NODE_SIZE = 10f;
    /** {@code QuadTree.maxObjectsPerNode}. */
    static final int MAX_OBJECTS = 5;

    final float x, y, width, height;
    private boolean leaf = true;
    private UnitTree botLeft, botRight, topLeft, topRight;
    private int[] objects = new int[MAX_OBJECTS + 1];
    private int count;

    UnitTree(float x, float y, float width, float height){
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** {@code World.getQuadBounds}: the map and {@code finalWorldBounds} on every side. */
    static UnitTree of(int tilesWide, int tilesHigh){
        return new UnitTree(-CellGrid.MARGIN, -CellGrid.MARGIN, tilesWide * 8 + CellGrid.MARGIN * 2, tilesHigh * 8 + CellGrid.MARGIN * 2);
    }

    /** A fork's copy, node for node. */
    UnitTree copy(){
        var out = new UnitTree(x, y, width, height);
        out.leaf = leaf;
        out.objects = objects.clone();
        out.count = count;
        if(botLeft != null){
            out.botLeft = botLeft.copy();
            out.botRight = botRight.copy();
            out.topLeft = topLeft.copy();
            out.topRight = topRight.copy();
        }
        return out;
    }

    /** {@code clear()}: the children are kept, emptied, as Arc keeps them. */
    void clear(){
        count = 0;
        if(!leaf){
            topLeft.clear();
            topRight.clear();
            botLeft.clear();
            botRight.clear();
        }
        leaf = true;
    }

    /**
     * {@code insert(obj)} (QuadTree.java:83): entry {@code e}, whose hitbox at the fill is the square of side
     * {@code sizes[e]} centred on ({@code xs[e]}, {@code ys[e]}). One outside the bounds is left out.
     */
    void insert(int e, float[] xs, float[] ys, float[] sizes){
        // hitbox(obj): Rect.setCentered(x, y, hitSize, hitSize)
        float s = sizes[e], rx = xs[e] - s / 2f, ry = ys[e] - s / 2f;
        if(!(x < rx + s && x + width > rx && y < ry + s && y + height > ry)) return;
        if(leaf && count + 1 > MAX_OBJECTS) split(xs, ys, sizes);
        if(leaf){
            add(e);
        }else{
            UnitTree child = fittingChild(rx, ry, s, s);
            if(child != null) child.insert(e, xs, ys, sizes);
            else add(e);
        }
    }

    /** {@code split()} (QuadTree.java:36). */
    private void split(float[] xs, float[] ys, float[] sizes){
        if(!leaf || width <= MIN_NODE_SIZE || height <= MIN_NODE_SIZE) return;
        if(botLeft == null){
            float subW = width / 2, subH = height / 2;
            botLeft = new UnitTree(x, y, subW, subH);
            botRight = new UnitTree(x + subW, y, subW, subH);
            topLeft = new UnitTree(x, y + subH, subW, subH);
            topRight = new UnitTree(x + subW, y + subH, subW, subH);
        }
        leaf = false;
        // transfer objects to children if they fit entirely in one
        for(int i = 0; i < count; i++){
            int e = objects[i];
            float s = sizes[e];
            UnitTree child = fittingChild(xs[e] - s / 2f, ys[e] - s / 2f, s, s);
            if(child != null){
                child.insert(e, xs, ys, sizes);
                // Seq(false): the last entry fills the hole, and is looked at next
                count--;
                objects[i] = objects[count];
                i--;
            }
        }
    }

    private void add(int e){
        if(count == objects.length) objects = Arrays.copyOf(objects, count * 2);
        objects[count++] = e;
    }

    /** {@code getFittingChild(boundingBox)}: the quadrant the box fits in entirely, or null. */
    private UnitTree fittingChild(float rx, float ry, float rw, float rh){
        float verticalMidpoint = x + (width / 2);
        float horizontalMidpoint = y + (height / 2);
        boolean topQuadrant = ry > horizontalMidpoint;
        boolean bottomQuadrant = ry < horizontalMidpoint && (ry + rh) < horizontalMidpoint;
        if(rx < verticalMidpoint && rx + rw < verticalMidpoint){
            if(topQuadrant) return topLeft;
            if(bottomQuadrant) return botLeft;
        }else if(rx > verticalMidpoint){
            if(topQuadrant) return topRight;
            if(bottomQuadrant) return botRight;
        }
        return null;
    }

    /** {@code bounds.overlaps(qx, qy, qw, qh)}. */
    private boolean overlaps(float qx, float qy, float qw, float qh){
        return x < qx + qw && x + width > qx && y < qy + qh && y + height > qy;
    }

    /**
     * {@code intersect(x, y, width, height, cons)} (QuadTree.java:239): every entry whose unit's current hitbox overlaps
     * the rectangle, in vanilla's order.
     */
    void intersect(float qx, float qy, float qw, float qh, Unit[] members, UnitVisitor visitor){
        if(!leaf){
            if(topLeft.overlaps(qx, qy, qw, qh)) topLeft.intersect(qx, qy, qw, qh, members, visitor);
            if(topRight.overlaps(qx, qy, qw, qh)) topRight.intersect(qx, qy, qw, qh, members, visitor);
            if(botLeft.overlaps(qx, qy, qw, qh)) botLeft.intersect(qx, qy, qw, qh, members, visitor);
            if(botRight.overlaps(qx, qy, qw, qh)) botRight.intersect(qx, qy, qw, qh, members, visitor);
        }
        // a visitor may query again, but never refills: the node's entries stay as they are
        for(int i = 0; i < count; i++){
            Unit unit = members[objects[i]];
            if(UnitGrid.overlaps(unit, qx, qy, qw, qh)) visitor.visit(unit);
        }
    }
}
