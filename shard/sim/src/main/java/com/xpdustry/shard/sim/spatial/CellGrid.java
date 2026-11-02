package com.xpdustry.shard.sim.spatial;

import java.util.*;

/**
 * A uniform grid rebuilt from scratch with a stable counting sort (note 29 §1.1, proto 29 {@code UniformGrid}). After
 * {@link #build}, the entries are sorted by cell, row-major, and by entry index inside a cell, so the cells
 * {@code cx0..cx1} of one row are one contiguous range of {@link #order}: a rectangle query is one loop per row.
 * Building allocates nothing once the arrays are large enough.
 *
 * <p>Two layouts give the same {@link #order} and the same ranges. Boxed: the counting sort over the cells of a box
 * around the entries, O(entries + box), with each cell's first slot in an array, so a range is two reads. A crowd, at
 * least one entry per {@value #SPARSE} cells, counts over the whole grid in one pass over the entries; fewer entries
 * count over their bounding box. Sorted, when that box has more than {@value #SPARSE} cells per entry: the entries
 * sorted by (cell, index), O(entries log entries + rows), and a range is two binary searches over its row's entries,
 * which an empty row skips.
 * A default map's grid has about 8,000 cells and its units a few dozen; counting over every cell, for every team grid
 * and the all-units grid each tick, was 13% of a hosted world's tick (W2.21).
 *
 * <p>The grid covers vanilla's quadtree bounds ({@code World.getQuadBounds}: the map plus 250 world units on each
 * side). Positions outside it go to the edge cells, so nothing is dropped.
 */
public final class CellGrid{
    /** World units per cell: four tiles, the size of a large unit. */
    public static final float CELL = 32f;
    /** {@code World.finalWorldBounds}. */
    public static final float MARGIN = 250f;
    /** Builds with fewer entries than one per this many cells use a bounding box, or the sorted layout if it is wider still. */
    static final int SPARSE = 16;

    public final float originX, originY;
    public final int width, height;
    /** Boxed: {@code start[b] .. start[b + 1]} is the range of the box's cell {@code b}, row-major, in {@link #order}. */
    private final int[] start;
    /** Sorted slot to entry index. */
    public int[] order;
    /** How many entries the last {@link #build} sorted. */
    public int count;
    /** Each entry's cell while building: first as {@code cy << 16 | cx}, then the box's or the grid's index. */
    private int[] cellOf;
    /** Whether the last build is boxed; if not, it is sorted: {@link #slotCell} holds the cells, {@link #start} is stale. */
    private boolean boxed;
    /** Boxed: the box's first cell, its width, and its first and last rows (none if the grid is empty). */
    private int boxX0, boxWidth, boxY0, boxY1 = -1;
    /** Sorted: the cell of each slot, ascending. */
    private int[] slotCell;
    /** Sorted: the first slot of each row, then {@link #count}. */
    private final int[] rowStart;
    /** Sorted: (cell, entry) pairs while sorting. */
    private long[] keys;

    /** A grid over a map of {@code tilesWide × tilesHigh} tiles. */
    public CellGrid(int tilesWide, int tilesHigh){
        originX = -MARGIN;
        originY = -MARGIN;
        width = width(tilesWide);
        height = width(tilesHigh);
        start = new int[width * height + 1];
        rowStart = new int[height + 1];
        order = new int[16];
        cellOf = new int[16];
        boxed = true;
    }

    private CellGrid(CellGrid source){
        originX = source.originX;
        originY = source.originY;
        width = source.width;
        height = source.height;
        start = source.start.clone();
        rowStart = source.rowStart.clone();
        order = source.order.clone();
        count = source.count;
        cellOf = source.cellOf.clone();
        boxed = source.boxed;
        boxX0 = source.boxX0;
        boxWidth = source.boxWidth;
        boxY0 = source.boxY0;
        boxY1 = source.boxY1;
        slotCell = source.slotCell == null ? null : source.slotCell.clone();
        keys = source.keys == null ? null : source.keys.clone();
    }

    /** How many cells span a map of {@code tiles} tiles and its margins. */
    public static int width(int tiles){
        return Math.max(1, (int)Math.ceil((tiles * 8f + MARGIN * 2f) / CELL));
    }

    public CellGrid copy(){
        return new CellGrid(this);
    }

    public int cellX(float x){
        int cell = (int)((x - originX) / CELL);
        return cell < 0 ? 0 : cell >= width ? width - 1 : cell;
    }

    public int cellY(float y){
        int cell = (int)((y - originY) / CELL);
        return cell < 0 ? 0 : cell >= height ? height - 1 : cell;
    }

    /** Sorts entries {@code 0 .. count - 1}, at positions ({@code x[i]}, {@code y[i]}), into cells. */
    public void build(float[] x, float[] y, int count){
        if(order.length < count){
            int capacity = Math.max(count, order.length * 3 / 2);
            order = new int[capacity];
            cellOf = new int[capacity];
        }
        this.count = count;
        if((long)count * SPARSE >= (long)width * height){
            // a crowd: its bounding box is likely most of the grid, and a second pass over the entries costs more
            boxed = true;
            boxX0 = 0;
            boxWidth = width;
            boxY0 = 0;
            boxY1 = height - 1;
            int cells = width * height;
            Arrays.fill(start, 0, cells + 1, 0);
            for(int i = 0; i < count; i++){
                int cell = cellY(y[i]) * width + cellX(x[i]);
                cellOf[i] = cell;
                start[cell + 1]++;
            }
            place(cells);
            return;
        }
        int x0 = width, x1 = -1, y0 = height, y1 = -1;
        for(int i = 0; i < count; i++){
            int cx = cellX(x[i]), cy = cellY(y[i]);
            // packed, so the box's pass needs no division: a grid is far narrower than 65,536 cells
            cellOf[i] = cy << 16 | cx;
            x0 = Math.min(x0, cx);
            x1 = Math.max(x1, cx);
            y0 = Math.min(y0, cy);
            y1 = Math.max(y1, cy);
        }
        boxed = count == 0 || (long)(x1 - x0 + 1) * (y1 - y0 + 1) <= (long)count * SPARSE;
        if(!boxed){
            buildSorted(count);
            return;
        }
        if(count == 0){
            boxY1 = -1;
            return;
        }
        boxX0 = x0;
        boxWidth = x1 - x0 + 1;
        boxY0 = y0;
        boxY1 = y1;
        int cells = boxWidth * (y1 - y0 + 1);
        Arrays.fill(start, 0, cells + 1, 0);
        for(int i = 0; i < count; i++){
            int packed = cellOf[i];
            // the box's cells are row-major too, so the order is the whole grid's
            int cell = ((packed >>> 16) - y0) * boxWidth + (packed & 0xffff) - x0;
            cellOf[i] = cell;
            start[cell + 1]++;
        }
        place(cells);
    }

    /** The counting sort's rest, once {@code start[c + 1]} counts the entries in box cell {@code c} of {@code cells}. */
    private void place(int cells){
        for(int c = 0; c < cells; c++) start[c + 1] += start[c];
        // start[c] is the cursor of cell c while placing, then shifted back
        for(int i = 0; i < count; i++) order[start[cellOf[i]]++] = i;
        for(int c = cells; c > 0; c--) start[c] = start[c - 1];
        start[0] = 0;
    }

    /** The sorted layout: the same stable order as the counting sort, since an entry's index breaks ties in its cell. */
    private void buildSorted(int count){
        if(keys == null || keys.length < order.length){
            keys = new long[order.length];
            slotCell = new int[order.length];
        }
        for(int i = 0; i < count; i++){
            int packed = cellOf[i];
            keys[i] = (long)((packed >>> 16) * width + (packed & 0xffff)) << 32 | i;
        }
        Arrays.sort(keys, 0, count);
        int row = 0;
        for(int k = 0; k < count; k++){
            order[k] = (int)keys[k];
            int cell = (int)(keys[k] >>> 32);
            slotCell[k] = cell;
            for(int cellRow = cell / width; row <= cellRow; row++) rowStart[row] = k;
        }
        for(; row <= height; row++) rowStart[row] = count;
    }

    /** The first slot of cells {@code cx0..} in row {@code cy}. */
    public int from(int cy, int cx0){
        return slot(cy, cx0);
    }

    /** One past the last slot of cells {@code ..cx1} in row {@code cy}. */
    public int to(int cy, int cx1){
        return slot(cy, cx1 + 1);
    }

    /** How many entries sit in cells before ({@code cx}, {@code cy}), where {@code cx} may be the row's end: its first slot. */
    private int slot(int cy, int cx){
        if(boxed){
            if(cy < boxY0) return 0;
            if(cy > boxY1) return count;
            // left of the box is the row's first cell in it, right of it the next row's
            int bx = cx <= boxX0 ? 0 : Math.min(cx - boxX0, boxWidth);
            return start[(cy - boxY0) * boxWidth + bx];
        }
        int cell = cy * width + cx;
        // every entry before the row's first slot sits in an earlier cell, every one from the next row's in a later one
        int low = rowStart[cy], high = rowStart[cy + 1];
        while(low < high){
            int mid = (low + high) >>> 1;
            if(slotCell[mid] < cell) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    /** Whether the last build is boxed, for tests. */
    boolean boxed(){
        return boxed;
    }
}
