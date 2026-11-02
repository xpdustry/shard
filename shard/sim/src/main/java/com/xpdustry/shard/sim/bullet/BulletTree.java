package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.sim.spatial.*;

import java.util.*;

/**
 * {@code Groups.bullet}'s tree as vanilla builds it: Arc's {@code QuadTree.fill(group.rawSeq())} (QuadTree.java:107)
 * over the group's bounds ({@code World.getQuadBounds}), node for node, from the bullets in the order the group held
 * them at the fill and at their fill positions. A query visits each node's children top-left, top-right, bottom-left,
 * bottom-right, then the node's own bullets in fill order, testing each one's current hitbox ({@code QuadTree.intersect}).
 *
 * <p>Entries are the bullets' pooled objects ({@link Bullets#object}), which a removal moves into the limbo without
 * changing, so a bullet removed after the fill is still found where vanilla's tree keeps it.
 */
final class BulletTree{
    /** {@code QuadTree.minNodeSize}, {@code maxObjectsPerNode}. */
    private static final float MIN_NODE_SIZE = 10f;
    private static final int MAX_OBJECTS_PER_NODE = 5;

    // nodes: bounds, children (-1 for a leaf), own entries [first, first + count) of objects
    private float[] nx = new float[16], ny = new float[16], nw = new float[16], nh = new float[16];
    private int[] topLeft = new int[16], topRight = new int[16], botLeft = new int[16], botRight = new int[16];
    private int[] first = new int[16], count = new int[16];
    private int nodes;
    private int[] objects = new int[16];
    private int objectCount;

    /**
     * Fills the tree with the bullets {@code fill} lists (pooled objects, in group order) on a map of {@code tilesWide ×
     * tilesHigh} tiles; {@code table} resolves each to its fill position.
     */
    void fill(Bullets table, int[] fill, int fillCount, int tilesWide, int tilesHigh){
        nodes = 0;
        objectCount = 0;
        if(objects.length < fillCount) objects = new int[Math.max(16, fillCount)];
        float[] boxX = new float[fillCount], boxY = new float[fillCount], size = new float[fillCount];
        int[] list = new int[fillCount];
        int listed = 0;
        for(int i = 0; i < fillCount; i++){
            Bullets at = table;
            int row = table.rowOfObject(fill[i]);
            if(row < 0){
                at = table.limbo();
                row = at.rowOfObject(fill[i]);
                // freed since the fill (only a test flushes the pool mid-tick)
                if(row < 0) continue;
            }
            // HitboxComp.hitbox: Rect.setCentered(x, y, hitSize, hitSize), at the fill
            size[i] = at.hitSize[row];
            boxX[i] = at.lastX[row] - size[i] / 2f;
            boxY[i] = at.lastY[row] - size[i] / 2f;
            list[listed++] = i;
        }
        float margin = CellGrid.MARGIN;
        node(-margin, -margin, tilesWide * 8 + margin * 2, tilesHigh * 8 + margin * 2);
        fill(0, list, listed, fill, boxX, boxY, size);
    }

    private int node(float x, float y, float w, float h){
        if(nodes == nx.length){
            int n = nodes * 2;
            nx = Arrays.copyOf(nx, n);
            ny = Arrays.copyOf(ny, n);
            nw = Arrays.copyOf(nw, n);
            nh = Arrays.copyOf(nh, n);
            topLeft = Arrays.copyOf(topLeft, n);
            topRight = Arrays.copyOf(topRight, n);
            botLeft = Arrays.copyOf(botLeft, n);
            botRight = Arrays.copyOf(botRight, n);
            first = Arrays.copyOf(first, n);
            count = Arrays.copyOf(count, n);
        }
        int at = nodes++;
        nx[at] = x;
        ny[at] = y;
        nw[at] = w;
        nh[at] = h;
        topLeft[at] = topRight[at] = botLeft[at] = botRight[at] = -1;
        return at;
    }

    private void fill(int node, int[] list, int size, int[] fill, float[] boxX, float[] boxY, float[] boxSize){
        float x = nx[node], y = ny[node], w = nw[node], h = nh[node];
        if(size <= MAX_OBJECTS_PER_NODE || w <= MIN_NODE_SIZE || h <= MIN_NODE_SIZE){
            own(node, list, size, fill);
            return;
        }
        float subW = w / 2, subH = h / 2;
        int bl = node(x, y, subW, subH), br = node(x + subW, y, subW, subH);
        int tl = node(x, y + subH, subW, subH), tr = node(x + subW, y + subH, subW, subH);
        botLeft[node] = bl;
        botRight[node] = br;
        topLeft[node] = tl;
        topRight[node] = tr;
        int[] inBL = new int[size], inBR = new int[size], inTL = new int[size], inTR = new int[size], own = new int[size];
        int cBL = 0, cBR = 0, cTL = 0, cTR = 0, cOwn = 0;
        // QuadTree.getFittingChild
        float vertical = x + (w / 2), horizontal = y + (h / 2);
        for(int i = 0; i < size; i++){
            int e = list[i];
            float bx = boxX[e], by = boxY[e], bs = boxSize[e];
            boolean top = by > horizontal;
            boolean bottom = by < horizontal && (by + bs) < horizontal;
            int child = -1;
            if(bx < vertical && bx + bs < vertical){
                if(top) child = tl;
                else if(bottom) child = bl;
            }else if(bx > vertical){
                if(top) child = tr;
                else if(bottom) child = br;
            }
            if(child == bl) inBL[cBL++] = e;
            else if(child == br) inBR[cBR++] = e;
            else if(child == tl) inTL[cTL++] = e;
            else if(child == tr) inTR[cTR++] = e;
            else own[cOwn++] = e;
        }
        own(node, own, cOwn, fill);
        fill(bl, inBL, cBL, fill, boxX, boxY, boxSize);
        fill(br, inBR, cBR, fill, boxX, boxY, boxSize);
        fill(tl, inTL, cTL, fill, boxX, boxY, boxSize);
        fill(tr, inTR, cTR, fill, boxX, boxY, boxSize);
    }

    private void own(int node, int[] list, int size, int[] fill){
        first[node] = objectCount;
        count[node] = size;
        for(int i = 0; i < size; i++) objects[objectCount++] = fill[list[i]];
    }

    /** {@code intersect(x, y, width, height, cons)}: every entry whose current hitbox overlaps, in the tree's order. */
    void intersect(Bullets table, float x, float y, float width, float height, Bullets.Member visitor){
        if(nodes > 0) intersect(0, table, x, y, width, height, visitor);
    }

    private void intersect(int node, Bullets table, float x, float y, float width, float height, Bullets.Member visitor){
        if(topLeft[node] != -1){
            int[] children = {topLeft[node], topRight[node], botLeft[node], botRight[node]};
            for(int child : children){
                if(nx[child] < x + width && nx[child] + nw[child] > x && ny[child] < y + height && ny[child] + nh[child] > y){
                    intersect(child, table, x, y, width, height, visitor);
                }
            }
        }
        for(int i = first[node], end = first[node] + count[node]; i < end; i++){
            int object = objects[i];
            Bullets at = table;
            int row = table.rowOfObject(object);
            if(row < 0){
                at = table.limbo();
                row = at.rowOfObject(object);
                // freed by the pool: no longer the bullet the tree was filled with
                if(row < 0) continue;
            }
            float size = at.hitSize[row], left = at.x[row] - size / 2f, bottom = at.y[row] - size / 2f;
            if(left < x + width && left + size > x && bottom < y + height && bottom + size > y) visitor.accept(at, row);
        }
    }
}
