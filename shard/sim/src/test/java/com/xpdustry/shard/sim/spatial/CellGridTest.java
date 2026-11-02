package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.math.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CellGrid}: both layouts place entries and answer ranges exactly as the counting sort over every cell does. */
class CellGridTest{
    @Test
    void everyLayoutMatchesTheCountingSort(){
        var rand = new Rand(21);
        int boxed = 0, sorted = 0;
        for(int tiles : new int[]{1, 20, 75, 300}){
            var grid = new CellGrid(tiles, tiles);
            int cells = grid.width * grid.height;
            // a few entries, about one per SPARSE cells, a crowd, spread over the map or clustered on a few cells
            for(int count : new int[]{0, 1, 2, 7, 40, cells / CellGrid.SPARSE - 1, cells / CellGrid.SPARSE, cells / CellGrid.SPARSE + 1, cells, 3 * cells}){
                if(count < 0) continue;
                for(boolean clustered : new boolean[]{false, true}){
                    float[] xs = new float[count], ys = new float[count];
                    for(int i = 0; i < count; i++){
                        // the margins and beyond, which clamp to the edge cells
                        float span = tiles * 8f + 2 * CellGrid.MARGIN + 200f;
                        xs[i] = clustered ? tiles * 4f + rand.range(20f) : rand.random(span) - CellGrid.MARGIN - 100f;
                        ys[i] = clustered ? tiles * 4f + rand.range(20f) : rand.random(span) - CellGrid.MARGIN - 100f;
                    }
                    grid.build(xs, ys, count);
                    if(grid.boxed()) boxed++;
                    else sorted++;
                    Reference reference = new Reference(grid, xs, ys, count);
                    String what = tiles + " tiles, " + count + (clustered ? " clustered" : " spread");
                    assertEquals(count, grid.count, what);
                    for(int k = 0; k < count; k++) assertEquals(reference.order[k], grid.order[k], what + ", slot " + k);
                    for(int cy = 0; cy < grid.height; cy++){
                        for(int cx = 0; cx < grid.width; cx++){
                            assertEquals(reference.start[cy * grid.width + cx], grid.from(cy, cx), what + ", from " + cx + "," + cy);
                            assertEquals(reference.start[cy * grid.width + cx + 1], grid.to(cy, cx), what + ", to " + cx + "," + cy);
                        }
                    }
                    CellGrid copy = grid.copy();
                    for(int cy = 0; cy < grid.height; cy++) assertEquals(grid.to(cy, grid.width - 1), copy.to(cy, grid.width - 1), what + ", copy");
                }
            }
        }
        assertTrue(boxed > 10 && sorted > 10, boxed + " boxed builds, " + sorted + " sorted");
    }

    /** The counting sort every layout must reproduce: entries by cell, then by index. */
    static final class Reference{
        final int[] start, order;

        Reference(CellGrid grid, float[] xs, float[] ys, int count){
            int cells = grid.width * grid.height;
            start = new int[cells + 1];
            order = new int[count];
            int[] cellOf = new int[count];
            for(int i = 0; i < count; i++){
                cellOf[i] = grid.cellY(ys[i]) * grid.width + grid.cellX(xs[i]);
                start[cellOf[i] + 1]++;
            }
            for(int c = 0; c < cells; c++) start[c + 1] += start[c];
            int[] cursor = start.clone();
            for(int i = 0; i < count; i++) order[cursor[cellOf[i]]++] = i;
        }
    }
}
