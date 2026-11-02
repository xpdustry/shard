package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.struct.*;

import java.util.*;

/**
 * Arc's {@code QuadTree} as {@code PhysicsWorld} uses it, float for float: rebuilt with {@link #fill} at the start of a
 * step, then queried with {@link #intersect} while the bodies move. Entries are indices into the layer's body arrays.
 * The tree hands out candidates in vanilla's order (top left, top right, bottom left, bottom right, then the node's own
 * entries), which a Gauss-Seidel pass depends on, so a crowd is pushed exactly as vanilla pushes it.
 *
 * <p>As vanilla's, a node splits above {@value #MAX_OBJECTS} entries until it is {@value #MIN_NODE_SIZE} world units
 * wide; an entry that straddles a split line stays in the parent. Placement uses the positions at {@link #fill}, the
 * queries the current ones ({@code hitbox()} is called again on every test). Nodes and their arrays are kept between
 * steps, as Arc keeps its children and scratch lists, so a settled layer allocates nothing.
 */
final class BodyTree{
    /** {@code QuadTree.minNodeSize}. */
    static final float MIN_NODE_SIZE = 10f;
    /** {@code QuadTree.maxObjectsPerNode}. */
    static final int MAX_OBJECTS = 5;

    final float x, y, width, height;
    private boolean leaf = true;
    private BodyTree botLeft, botRight, topLeft, topRight;
    private int[] objects = new int[MAX_OBJECTS];
    private int count;
    /** The partition of the last {@link #fill}, by quadrant; scratch, as {@code fillBL}… */
    private int[] bl, br, tl, tr;
    private int nbl, nbr, ntl, ntr;

    BodyTree(float x, float y, float width, float height){
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** {@code World.getQuadBounds}: the map and {@code finalWorldBounds} on every side. */
    static BodyTree of(int tilesWide, int tilesHigh){
        return new BodyTree(-CellGrid.MARGIN, -CellGrid.MARGIN, tilesWide * 8 + CellGrid.MARGIN * 2, tilesHigh * 8 + CellGrid.MARGIN * 2);
    }

    BodyTree copy(){
        var out = new BodyTree(x, y, width, height);
        out.leaf = leaf;
        out.objects = objects.clone();
        out.count = count;
        if(botLeft != null){
            out.botLeft = botLeft.copy();
            out.botRight = botRight.copy();
            out.topLeft = topLeft.copy();
            out.topRight = topRight.copy();
        }
        if(bl != null){
            out.bl = bl.clone();
            out.br = br.clone();
            out.tl = tl.clone();
            out.tr = tr.clone();
        }
        out.nbl = nbl;
        out.nbr = nbr;
        out.ntl = ntl;
        out.ntr = ntr;
        return out;
    }

    /** {@code clear()}. */
    private void clear(){
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
     * {@code fill(list)}: the tree rebuilt from entries {@code list[0 .. size - 1]}, each a body of radius
     * {@code radius[i]} centred on ({@code xs[i]}, {@code ys[i]}).
     */
    void fill(int[] list, int size, float[] xs, float[] ys, float[] radius){
        clear();
        if(size <= MAX_OBJECTS || width <= MIN_NODE_SIZE || height <= MIN_NODE_SIZE){
            objects = ensure(objects, size);
            System.arraycopy(list, 0, objects, 0, size);
            count = size;
            return;
        }
        if(botLeft == null){
            float subW = width / 2, subH = height / 2;
            botLeft = new BodyTree(x, y, subW, subH);
            botRight = new BodyTree(x + subW, y, subW, subH);
            topLeft = new BodyTree(x, y + subH, subW, subH);
            topRight = new BodyTree(x + subW, y + subH, subW, subH);
        }
        leaf = false;
        if(bl == null){
            bl = new int[MAX_OBJECTS];
            br = new int[MAX_OBJECTS];
            tl = new int[MAX_OBJECTS];
            tr = new int[MAX_OBJECTS];
        }
        nbl = nbr = ntl = ntr = 0;

        // single partitioning pass instead of one split()-check per insert
        for(int i = 0; i < size; i++){
            int entry = list[i];
            // PhysicsBody.hitbox: setCentered(x, y, radius * 2, radius * 2)
            float w = radius[entry] * 2, rx = xs[entry] - w / 2f, ry = ys[entry] - w / 2f;
            switch(fittingChild(rx, ry, w, w)){
                case BOT_LEFT -> {
                    bl = ensure(bl, nbl + 1);
                    bl[nbl++] = entry;
                }
                case BOT_RIGHT -> {
                    br = ensure(br, nbr + 1);
                    br[nbr++] = entry;
                }
                case TOP_LEFT -> {
                    tl = ensure(tl, ntl + 1);
                    tl[ntl++] = entry;
                }
                case TOP_RIGHT -> {
                    tr = ensure(tr, ntr + 1);
                    tr[ntr++] = entry;
                }
                default -> {
                    // doesn't fit any quadrant, stays in this node
                    objects = ensure(objects, count + 1);
                    objects[count++] = entry;
                }
            }
        }

        botLeft.fill(bl, nbl, xs, ys, radius);
        botRight.fill(br, nbr, xs, ys, radius);
        topLeft.fill(tl, ntl, xs, ys, radius);
        topRight.fill(tr, ntr, xs, ys, radius);
    }

    private static final int NONE = 0, BOT_LEFT = 1, BOT_RIGHT = 2, TOP_LEFT = 3, TOP_RIGHT = 4;

    /** {@code getFittingChild(boundingBox)}: the quadrant the box fits in entirely, or none. */
    private int fittingChild(float rx, float ry, float rw, float rh){
        float verticalMidpoint = x + (width / 2);
        float horizontalMidpoint = y + (height / 2);
        boolean topQuadrant = ry > horizontalMidpoint;
        boolean bottomQuadrant = ry < horizontalMidpoint && (ry + rh) < horizontalMidpoint;
        if(rx < verticalMidpoint && rx + rw < verticalMidpoint){
            if(topQuadrant) return TOP_LEFT;
            if(bottomQuadrant) return BOT_LEFT;
        }else if(rx > verticalMidpoint){
            if(topQuadrant) return TOP_RIGHT;
            if(bottomQuadrant) return BOT_RIGHT;
        }
        return NONE;
    }

    /** {@code bounds.overlaps(qx, qy, qw, qh)}. */
    private boolean overlaps(float qx, float qy, float qw, float qh){
        return x < qx + qw && x + width > qx && y < qy + qh && y + height > qy;
    }

    /**
     * {@code intersect(x, y, width, height, Seq out)}: appends to {@code out} every entry whose box, at the bodies'
     * current positions, overlaps the query, in vanilla's order.
     */
    void intersect(float qx, float qy, float qw, float qh, UnitPhysics.Body[] bodies, IntSeq out){
        if(!leaf){
            if(topLeft.overlaps(qx, qy, qw, qh)) topLeft.intersect(qx, qy, qw, qh, bodies, out);
            if(topRight.overlaps(qx, qy, qw, qh)) topRight.intersect(qx, qy, qw, qh, bodies, out);
            if(botLeft.overlaps(qx, qy, qw, qh)) botLeft.intersect(qx, qy, qw, qh, bodies, out);
            if(botRight.overlaps(qx, qy, qw, qh)) botRight.intersect(qx, qy, qw, qh, bodies, out);
        }
        for(int i = 0; i < count; i++){
            UnitPhysics.Body item = bodies[objects[i]];
            float os = item.radius * 2, ox = item.x - os / 2f, oy = item.y - os / 2f;
            if(ox < qx + qw && ox + os > qx && oy < qy + qh && oy + os > qy) out.add(objects[i]);
        }
    }

    private static int[] ensure(int[] array, int size){
        return size <= array.length ? array : Arrays.copyOf(array, Math.max(size, array.length * 2));
    }
}
