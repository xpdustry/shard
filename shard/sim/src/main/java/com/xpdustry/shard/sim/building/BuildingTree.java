package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * A team's {@code buildingTree} (Teams.java:295): Arc's {@code QuadTree} over the buildings the indexer filed for the
 * team, each by its footprint. Queries visit the top-left, top-right, bottom-left and bottom-right children, then the
 * node's own objects, in their list order; that order is history (unordered lists, splits that move objects down,
 * removals that merge them back), and vanilla's power autolink and targeting break distance ties by it. So the whole
 * shape is {@link Persistence#AUTHORITATIVE}: {@link BlockIndex} stores it node by node.
 *
 * <p>A port of {@code QuadTree.java} (Arc), step for step: a leaf splits when a sixth object arrives (unless it is no
 * wider or taller than {@value #MIN_NODE_SIZE} world units), an object goes to the one child that holds its whole
 * box or stays in the node, and a node that is left with at most {@value #MAX_OBJECTS} objects after a removal merges
 * its children back.
 *
 * <p>Owner: WP37, at WP20's indexer.
 */
public final class BuildingTree{
    /** {@code minNodeSize}. */
    static final float MIN_NODE_SIZE = 10f;
    /** {@code maxObjectsPerNode}. */
    static final int MAX_OBJECTS = 5;
    private static final Building[] NONE = {};

    private final float x, y, width, height;
    private Building[] objects = NONE;
    private int size;
    private BuildingTree botLeft, botRight, topLeft, topRight;
    private boolean leaf = true;
    private int totalObjects;

    /** {@code new QuadTree(new Rect(x, y, width, height))}. */
    BuildingTree(float x, float y, float width, float height){
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    private void split(){
        if(!leaf || width <= MIN_NODE_SIZE || height <= MIN_NODE_SIZE) return;
        float subW = width / 2, subH = height / 2;
        if(botLeft == null){
            botLeft = new BuildingTree(x, y, subW, subH);
            botRight = new BuildingTree(x + subW, y, subW, subH);
            topLeft = new BuildingTree(x, y + subH, subW, subH);
            topRight = new BuildingTree(x + subW, y + subH, subW, subH);
        }
        leaf = false;
        // objects that fit entirely in a child move there; the last object fills each hole
        for(int i = 0; i < size; i++){
            Building obj = objects[i];
            BuildingTree child = fittingChild(obj);
            if(child != null){
                child.insert(obj);
                size--;
                objects[i] = objects[size];
                objects[size] = null;
                i--;
            }
        }
    }

    private void unsplit(){
        if(leaf) return;
        addAll(botLeft);
        addAll(botRight);
        addAll(topLeft);
        addAll(topRight);
        botLeft.clear();
        botRight.clear();
        topLeft.clear();
        topRight.clear();
        leaf = true;
    }

    private void addAll(BuildingTree child){
        for(int i = 0; i < child.size; i++) add(child.objects[i]);
    }

    private void add(Building obj){
        if(size == objects.length) objects = Arrays.copyOf(objects, Math.max(8, (int)(size * 1.75f)));
        objects[size++] = obj;
    }

    /** {@code insert(obj)}: ignored if its box is outside this node. */
    void insert(Building obj){
        if(!overlaps(obj)) return;
        totalObjects++;
        if(leaf && size + 1 > MAX_OBJECTS) split();
        if(leaf){
            add(obj);
        }else{
            BuildingTree child = fittingChild(obj);
            if(child != null){
                child.insert(obj);
            }else{
                add(obj);
            }
        }
    }

    /** {@code remove(obj)}. Vanilla quirk kept: the merge test reads the count from before the removal. */
    boolean remove(Building obj){
        boolean result;
        if(leaf){
            result = removeHere(obj);
        }else{
            BuildingTree child = fittingChild(obj);
            result = child != null ? child.remove(obj) : removeHere(obj);
            if(totalObjects <= MAX_OBJECTS) unsplit();
        }
        if(result) totalObjects--;
        return result;
    }

    /** {@code Seq.remove(value, true)} on an unordered seq: the last object takes its place. */
    private boolean removeHere(Building obj){
        for(int i = 0; i < size; i++){
            if(objects[i] == obj){
                objects[i] = objects[--size];
                objects[size] = null;
                return true;
            }
        }
        return false;
    }

    /** {@code clear()}. */
    void clear(){
        Arrays.fill(objects, 0, size, null);
        size = 0;
        totalObjects = 0;
        if(!leaf){
            topLeft.clear();
            topRight.clear();
            botLeft.clear();
            botRight.clear();
        }
        leaf = true;
    }

    /** {@code getFittingChild(box)}: the child that holds the building's whole box, or null. */
    private BuildingTree fittingChild(Building obj){
        float half = obj.block().size() * Footprint.TILE_SIZE / 2f;
        float bx = obj.x() - half, by = obj.y() - half, bw = half * 2, bh = half * 2;
        float verticalMidpoint = x + (width / 2);
        float horizontalMidpoint = y + (height / 2);
        boolean topQuadrant = by > horizontalMidpoint;
        boolean bottomQuadrant = by < horizontalMidpoint && (by + bh) < horizontalMidpoint;
        if(bx < verticalMidpoint && bx + bw < verticalMidpoint){
            if(topQuadrant) return topLeft;
            if(bottomQuadrant) return botLeft;
        }else if(bx > verticalMidpoint){
            if(topQuadrant) return topRight;
            if(bottomQuadrant) return botRight;
        }
        return null;
    }

    /** {@code bounds.overlaps(hitbox)}. */
    private boolean overlaps(Building obj){
        float half = obj.block().size() * Footprint.TILE_SIZE / 2f;
        return overlaps(obj.x() - half, obj.y() - half, half * 2, half * 2);
    }

    /** {@code Rect.overlaps(rx, ry, rw, rh)} of this node's bounds. */
    private boolean overlaps(float rx, float ry, float rw, float rh){
        return x < rx + rw && x + width > rx && y < ry + rh && y + height > ry;
    }

    /**
     * {@code intersect(x, y, width, height, out)}: every building whose box overlaps the rectangle (world units,
     * {@code x, y} its bottom-left corner), in the tree's order.
     */
    public void intersect(float qx, float qy, float qw, float qh, Consumer<Building> out){
        if(!leaf){
            if(topLeft.overlaps(qx, qy, qw, qh)) topLeft.intersect(qx, qy, qw, qh, out);
            if(topRight.overlaps(qx, qy, qw, qh)) topRight.intersect(qx, qy, qw, qh, out);
            if(botLeft.overlaps(qx, qy, qw, qh)) botLeft.intersect(qx, qy, qw, qh, out);
            if(botRight.overlaps(qx, qy, qw, qh)) botRight.intersect(qx, qy, qw, qh, out);
        }
        for(int i = 0; i < size; i++){
            Building item = objects[i];
            float half = item.block().size() * Footprint.TILE_SIZE / 2f;
            // Rect.overlaps of the item's box against the query
            float ix = item.x() - half, iy = item.y() - half, iw = half * 2, ih = half * 2;
            if(ix < qx + qw && ix + iw > qx && iy < qy + qh && iy + ih > qy) out.accept(item);
        }
    }

    /** {@code any(x, y, width, height)}: whether some building's box overlaps the rectangle, as {@link #intersect} finds them. */
    public boolean any(float qx, float qy, float qw, float qh){
        if(!leaf){
            if(topLeft.overlaps(qx, qy, qw, qh) && topLeft.any(qx, qy, qw, qh)) return true;
            if(topRight.overlaps(qx, qy, qw, qh) && topRight.any(qx, qy, qw, qh)) return true;
            if(botLeft.overlaps(qx, qy, qw, qh) && botLeft.any(qx, qy, qw, qh)) return true;
            if(botRight.overlaps(qx, qy, qw, qh) && botRight.any(qx, qy, qw, qh)) return true;
        }
        for(int i = 0; i < size; i++){
            Building item = objects[i];
            float half = item.block().size() * Footprint.TILE_SIZE / 2f;
            float ix = item.x() - half, iy = item.y() - half, iw = half * 2, ih = half * 2;
            if(ix < qx + qw && ix + iw > qx && iy < qy + qh && iy + ih > qy) return true;
        }
        return false;
    }

    /**
     * {@code getObjects(out)} (QuadTree.java:371): every building, this node's own first, then topLeft, topRight,
     * botLeft and botRight. Not {@link #intersect}'s order, which visits the children first.
     */
    public void getObjects(Consumer<Building> out){
        for(int i = 0; i < size; i++) out.accept(objects[i]);
        if(!leaf){
            topLeft.getObjects(out);
            topRight.getObjects(out);
            botLeft.getObjects(out);
            botRight.getObjects(out);
        }
    }

    /** How many buildings the tree holds. */
    public int size(){
        return totalObjects;
    }

    // copies and checkpoints

    BuildingTree copy(CopyContext context){
        var out = new BuildingTree(x, y, width, height);
        out.objects = size == 0 ? NONE : new Building[size];
        for(int i = 0; i < size; i++) out.objects[i] = context.map(objects[i]);
        out.size = size;
        out.leaf = leaf;
        out.totalObjects = totalObjects;
        if(!leaf){
            out.botLeft = botLeft.copy(context);
            out.botRight = botRight.copy(context);
            out.topLeft = topLeft.copy(context);
            out.topRight = topRight.copy(context);
        }
        return out;
    }

    /**
     * Appends the shape in pre-order (each node: leaf, total, object count; then its children, if not a leaf, in
     * top-left, top-right, bottom-left, bottom-right order) and the objects in the same order. A leaf's children are
     * always empty, so they aren't stored.
     */
    void write(IntSeq shape, List<Building> members){
        shape.add(leaf ? 1 : 0, totalObjects, size);
        for(int i = 0; i < size; i++) members.add(objects[i]);
        if(!leaf){
            topLeft.write(shape, members);
            topRight.write(shape, members);
            botLeft.write(shape, members);
            botRight.write(shape, members);
        }
    }

    /** Reads what {@link #write} stored into this fresh node, from {@code at[0]} in the shape and {@code at[1]} in the members. */
    void read(IntSeq shape, Building[] members, int[] at){
        if(at[0] + 3 > shape.size) throw new com.xpdustry.shard.sim.persist.CheckpointException("a building tree's shape is cut short");
        leaf = shape.get(at[0]) == 1;
        totalObjects = shape.get(at[0] + 1);
        size = shape.get(at[0] + 2);
        at[0] += 3;
        if(size < 0 || at[1] + size > members.length) throw new com.xpdustry.shard.sim.persist.CheckpointException("a building tree's members are cut short");
        objects = size == 0 ? NONE : Arrays.copyOfRange(members, at[1], at[1] + size);
        at[1] += size;
        if(!leaf){
            float subW = width / 2, subH = height / 2;
            topLeft = new BuildingTree(x, y + subH, subW, subH);
            topRight = new BuildingTree(x + subW, y + subH, subW, subH);
            botLeft = new BuildingTree(x, y, subW, subH);
            botRight = new BuildingTree(x + subW, y, subW, subH);
            topLeft.read(shape, members, at);
            topRight.read(shape, members, at);
            botLeft.read(shape, members, at);
            botRight.read(shape, members, at);
        }
    }

    void hash(Hasher hasher){
        hasher.bool(leaf).i(totalObjects).i(size);
        for(int i = 0; i < size; i++) hasher.i(objects[i].id());
        if(!leaf){
            topLeft.hash(hasher);
            topRight.hash(hasher);
            botLeft.hash(hasher);
            botRight.hash(hasher);
        }
    }
}
