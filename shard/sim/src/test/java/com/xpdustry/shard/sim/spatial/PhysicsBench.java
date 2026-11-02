package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * What vanilla's candidate order costs: one physics step of a layer ({@link UnitPhysics.Layer#step}, on
 * {@link BodyTree}, vanilla's quadtree) against the uniform grid it replaced ({@link CellGrid}, cell order, note 29's
 * recommendation), on the same bodies: a settled crowd (a jittered lattice where each body touches its neighbours) and
 * a dense one (twice as packed). Each run restores the bodies, so every run does the same work. Writes
 * {@code build/reports/physics-bench.txt}.
 */
@Tag("slow")
class PhysicsBench{
    static final int WARMUP = 200, RUNS = 400;

    @Test
    void measure() throws IOException{
        var report = new StringBuilder();
        for(int bodies : new int[]{500, 2000, 5000}){
            for(float spacing : new float[]{9f, 6f}){
                report.append(String.format("%5d bodies, spacing %.0f: tree %s, grid %s%n", bodies, spacing,
                    time(bodies, spacing, false), time(bodies, spacing, true)));
            }
        }
        String text = report.toString();
        System.out.print(text);
        Path out = Path.of("build", "reports", "physics-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    /** The median time of one step, in microseconds. */
    static String time(int count, float spacing, boolean grid){
        int side = (int)Math.ceil(Math.sqrt(count));
        int tiles = (int)(side * spacing / 8f) + 16;
        var layer = new UnitPhysics.Layer(new Rand(7), tiles, tiles);
        var random = new SplittableRandom(3);
        float[] x = new float[count], y = new float[count];
        for(int i = 0; i < count; i++){
            var body = new UnitPhysics.Body();
            x[i] = 40f + (i % side) * spacing + random.nextFloat();
            y[i] = 40f + (i / side) * spacing + random.nextFloat();
            body.radius = 8f * 0.6f;
            body.mass = 8f * 8f * Mathf.pi;
            layer.add(body);
        }
        var reference = new GridStep(tiles);
        long[] runs = new long[RUNS];
        for(int run = -WARMUP; run < RUNS; run++){
            for(int i = 0; i < count; i++){
                layer.bodies[i].x = x[i];
                layer.bodies[i].y = y[i];
            }
            long start = System.nanoTime();
            if(grid) reference.step(layer);
            else layer.step();
            long elapsed = System.nanoTime() - start;
            if(run >= 0) runs[run] = elapsed;
        }
        Arrays.sort(runs);
        return String.format("%7.1f µs", runs[RUNS / 2] / 1000.0);
    }

    /** The step as it was on the grid: the same pushes, candidates in cell order. */
    static final class GridStep{
        final CellGrid grid;
        float[] xs = new float[16], ys = new float[16];
        int[] candidates = new int[16];

        GridStep(int tiles){
            grid = new CellGrid(tiles, tiles);
        }

        void step(UnitPhysics.Layer layer){
            int size = layer.size;
            UnitPhysics.Body[] bodies = layer.bodies;
            if(xs.length < size){
                xs = new float[bodies.length];
                ys = new float[bodies.length];
            }
            float maxRadius = 0f;
            for(int i = 0; i < size; i++){
                xs[i] = bodies[i].x;
                ys[i] = bodies[i].y;
                maxRadius = Math.max(maxRadius, bodies[i].radius);
                bodies[i].collided = false;
            }
            grid.build(xs, ys, size);
            int[] order = grid.order;
            var vec = new Vec2();
            for(int i = 0; i < size; i++){
                UnitPhysics.Body body = bodies[i];
                float qx = body.x - body.radius, qy = body.y - body.radius, qs = body.radius * 2, reach = maxRadius + 8f;
                int cx0 = grid.cellX(qx - reach), cx1 = grid.cellX(qx + qs + reach), cy0 = grid.cellY(qy - reach), cy1 = grid.cellY(qy + qs + reach);
                int found = 0;
                for(int cy = cy0; cy <= cy1; cy++){
                    for(int k = grid.from(cy, cx0), end = grid.to(cy, cx1); k < end; k++){
                        UnitPhysics.Body other = bodies[order[k]];
                        float os = other.radius * 2, ox = other.x - os / 2f, oy = other.y - os / 2f;
                        if(ox < qx + qs && ox + os > qx && oy < qy + qs && oy + os > qy){
                            if(found == candidates.length) candidates = Arrays.copyOf(candidates, found * 2);
                            candidates[found++] = order[k];
                        }
                    }
                }
                for(int j = 0; j < found; j++){
                    UnitPhysics.Body other = bodies[candidates[j]];
                    if(other == body || other.collided) continue;
                    float rs = body.radius + other.radius, dx = body.x - other.x, dy = body.y - other.y, dst2 = dx * dx + dy * dy;
                    if(dst2 < rs * rs){
                        float dst = Mathf.sqrt(dst2);
                        vec.set(dx, dy).setLength(rs - dst);
                        float ms = body.mass + other.mass, m1 = other.mass / ms, m2 = body.mass / ms;
                        body.x += vec.x * m1 / 1.25f;
                        body.y += vec.y * m1 / 1.25f;
                        other.x -= vec.x * m2 / 1.25f;
                        other.y -= vec.y * m2 / 1.25f;
                    }
                }
                body.collided = true;
            }
        }
    }
}
