package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * One of vanilla's unit quadtrees ({@code TeamData.unitTree}, {@code Groups.unit}'s tree) as a {@link CellGrid}.
 *
 * <p>The members, the units the tree was filled with, in fill order, are {@link Persistence#AUTHORITATIVE}: like
 * vanilla's tree, they keep units removed since the fill and miss units added since. Where each member sits is derived:
 * {@link #sort()} places members by their position when it runs, and {@link #expire()} drops the placement at the end
 * of each tick, so the next query places them again from state a checkpoint holds.
 *
 * <p>Queries test each candidate's current hitbox, as vanilla's tree does, and look {@link #SLACK} world units beyond
 * the largest member so that members which moved since they were placed are still found.
 *
 * <p>Each member's hitbox at the fill is kept too ({@link #fillX}, {@link #fillY}, {@link #fillSize}), authoritative,
 * since the units move on: {@link #intersectTree} walks vanilla's own quadtree ({@link UnitTree}), built from them on its
 * first use after a fill, for the queries whose results follow the tree's order.
 */
public final class UnitGrid implements Checkpointable{
    /** How far a member may move after placement and still be found; one tile is more than a unit moves per tick. */
    static final float SLACK = 8f;

    @Derived("bind(): sized by the map, then placed again if it was placed")
    private CellGrid cells;
    private Unit[] members;
    private int size;
    /**
     * Whether the members are placed. Stored: a placement survives a tick boundary only when a query ran after the
     * tick (IndexExpiry drops it at the end of each), and nothing moves units between ticks, so {@link #bind} places
     * the members again exactly.
     */
    private boolean sorted;
    /** The largest member hit size at placement. */
    @Derived("sort() in bind(), from the members' hit sizes")
    private float maxSize;
    @Derived("sort() in bind(), from the members' positions")
    private float[] xs, ys;
    /** Each member's hitbox centre and side when it joined the fill: where vanilla's tree placed it. */
    private float[] fillX, fillY, fillSize;
    @Derived("the map's size, from bind()")
    private int tilesWide, tilesHigh;
    @Derived("built from the fill hitboxes on the first intersectTree after a fill")
    private UnitTree tree;
    @Derived("whether tree holds this fill")
    private boolean treeBuilt;

    /** A blank grid for a checkpoint to fill; its index binds it to the map. */
    public UnitGrid(){
        this(0, 0);
    }

    UnitGrid(int tilesWide, int tilesHigh){
        bind(tilesWide, tilesHigh);
        members = new Unit[16];
        xs = new float[16];
        ys = new float[16];
        fillX = new float[16];
        fillY = new float[16];
        fillSize = new float[16];
    }

    /** Sizes the grid for the map, and places the members again if a restored grid was placed. */
    void bind(int tilesWide, int tilesHigh){
        cells = new CellGrid(tilesWide, tilesHigh);
        this.tilesWide = tilesWide;
        this.tilesHigh = tilesHigh;
        tree = null;
        treeBuilt = false;
        if(sorted) sort();
    }

    /** The members in fill order, by reference; the array's spare capacity isn't state. */
    @Override
    public void write(CheckpointOut out){
        out.refs("members", members, size).i("size", size).bool("sorted", sorted)
            .floats("fillX", fillX, size).floats("fillY", fillY, size).floats("fillSize", fillSize, size);
    }

    @Override
    public void read(CheckpointIn in){
        Unit[] stored = in.refs("members", Unit.class);
        size = in.i("size");
        if(stored == null || stored.length != size) throw new CheckpointException("a unit grid's members are inconsistent");
        for(Unit unit : stored) if(unit == null) throw new CheckpointException("a unit grid holds a null");
        members = Arrays.copyOf(stored, Math.max(16, size));
        sorted = in.bool("sorted");
        float[] x = in.floats("fillX"), y = in.floats("fillY"), side = in.floats("fillSize");
        if(x == null || y == null || side == null || x.length != size || y.length != size || side.length != size){
            throw new CheckpointException("a unit grid's fill hitboxes are inconsistent");
        }
        fillX = Arrays.copyOf(x, members.length);
        fillY = Arrays.copyOf(y, members.length);
        fillSize = Arrays.copyOf(side, members.length);
    }

    private UnitGrid(UnitGrid source, CopyContext context){
        cells = source.cells.copy();
        members = new Unit[source.members.length];
        size = source.size;
        for(int i = 0; i < size; i++) members[i] = context.map(source.members[i]);
        sorted = source.sorted;
        maxSize = source.maxSize;
        xs = source.xs.clone();
        ys = source.ys.clone();
        fillX = source.fillX.clone();
        fillY = source.fillY.clone();
        fillSize = source.fillSize.clone();
        tilesWide = source.tilesWide;
        tilesHigh = source.tilesHigh;
        tree = source.tree == null ? null : source.tree.copy();
        treeBuilt = source.treeBuilt;
    }

    UnitGrid copy(CopyContext context){
        return new UnitGrid(this, context);
    }

    /** Starts a fill: no members. */
    void clear(){
        Arrays.fill(members, 0, size, null);
        size = 0;
        sorted = false;
        treeBuilt = false;
    }

    /** A member joins the fill, its hitbox now recorded. */
    void add(Unit unit){
        if(size == members.length){
            members = Arrays.copyOf(members, size * 7 / 4 + 1);
            fillX = Arrays.copyOf(fillX, members.length);
            fillY = Arrays.copyOf(fillY, members.length);
            fillSize = Arrays.copyOf(fillSize, members.length);
        }
        fillX[size] = unit.x;
        fillY[size] = unit.y;
        fillSize[size] = unit.hitSize;
        members[size++] = unit;
        sorted = false;
        treeBuilt = false;
    }

    int size(){
        return size;
    }

    Unit member(int index){
        return members[index];
    }

    /** Places every member at its current position. */
    void sort(){
        if(xs.length < size){
            xs = new float[members.length];
            ys = new float[members.length];
        }
        float max = 0f;
        for(int i = 0; i < size; i++){
            Unit unit = members[i];
            xs[i] = unit.x;
            ys[i] = unit.y;
            if(unit.hitSize > max) max = unit.hitSize;
        }
        maxSize = max;
        cells.build(xs, ys, size);
        sorted = true;
    }

    /** Drops the placement; the next query places the members again. */
    void expire(){
        sorted = false;
    }

    /**
     * Vanilla's {@code QuadTree.intersect(x, y, width, height, cons)}: every member whose hitbox
     * ({@code setCentered(x, y, hitSize, hitSize)}) overlaps the rectangle, by Arc's strict {@code Rect.overlaps}. Visits
     * in cell order, not the quadtree's.
     */
    void intersect(float x, float y, float width, float height, UnitVisitor visitor){
        if(size == 0) return;
        if(!sorted) sort();
        float reach = maxSize / 2f + SLACK;
        CellGrid cells = this.cells;
        int cx0 = cells.cellX(x - reach), cx1 = cells.cellX(x + width + reach);
        int cy0 = cells.cellY(y - reach), cy1 = cells.cellY(y + height + reach);
        int[] order = cells.order;
        for(int cy = cy0; cy <= cy1; cy++){
            for(int k = cells.from(cy, cx0), end = cells.to(cy, cx1); k < end; k++){
                Unit unit = members[order[k]];
                if(overlaps(unit, x, y, width, height)) visitor.visit(unit);
            }
        }
    }

    /**
     * Vanilla's {@code QuadTree.intersect(x, y, width, height, cons)} in its own order: the tree {@code insert} built
     * from the members at their fill hitboxes ({@link UnitTree}), each tested at its current one.
     */
    void intersectTree(float x, float y, float width, float height, UnitVisitor visitor){
        if(size == 0) return;
        if(!treeBuilt){
            if(tree == null) tree = UnitTree.of(tilesWide, tilesHigh);
            tree.clear();
            for(int i = 0; i < size; i++) tree.insert(i, fillX, fillY, fillSize);
            treeBuilt = true;
        }
        tree.intersect(x, y, width, height, members, visitor);
    }

    /** Vanilla's {@code QuadTree.intersect(x, y, width, height, boolf)}: stops at the first member {@code filter} accepts. */
    boolean check(float x, float y, float width, float height, UnitFilter filter){
        if(size == 0) return false;
        if(!sorted) sort();
        float reach = maxSize / 2f + SLACK;
        CellGrid cells = this.cells;
        int cx0 = cells.cellX(x - reach), cx1 = cells.cellX(x + width + reach);
        int cy0 = cells.cellY(y - reach), cy1 = cells.cellY(y + height + reach);
        int[] order = cells.order;
        for(int cy = cy0; cy <= cy1; cy++){
            for(int k = cells.from(cy, cx0), end = cells.to(cy, cx1); k < end; k++){
                Unit unit = members[order[k]];
                if(overlaps(unit, x, y, width, height) && filter.test(unit)) return true;
            }
        }
        return false;
    }

    /** Vanilla's {@code QuadTree.any}. */
    boolean any(float x, float y, float width, float height){
        return check(x, y, width, height, UnitFilter.ANY);
    }

    /** Arc's {@code hitbox.overlaps(x, y, width, height)} for {@code hitbox = setCentered(unit.x, unit.y, hitSize, hitSize)}. */
    static boolean overlaps(Unit unit, float x, float y, float width, float height){
        float size = unit.hitSize, left = unit.x - size / 2f, bottom = unit.y - size / 2f;
        return left < x + width && left + size > x && bottom < y + height && bottom + size > y;
    }

    void hash(Hasher hasher){
        hash(hasher, true);
    }

    /** {@code fills} false leaves the fill hitboxes out, as the hash was before W6.1 ({@code SpatialHashToggleTest}). */
    void hash(Hasher hasher, boolean fills){
        hasher.i(size);
        for(int i = 0; i < size; i++) hasher.i(members[i].id());
        if(!fills) return;
        for(int i = 0; i < size; i++) hasher.f(fillX[i]).f(fillY[i]).f(fillSize[i]);
    }
}
