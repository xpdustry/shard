package com.xpdustry.shard.common.geom;

import com.xpdustry.shard.common.math.*;

import java.util.*;

/**
 * The direction tables and grid walks of Arc's {@code arc.math.geom.Geometry}, in Arc's order: {@code d4} starts at +x
 * and turns counter-clockwise, which is also block rotation order (0 = right, 1 = up, 2 = left, 3 = down). The point
 * tables are immutable lists of {@link Point2}; kernels can use the {@code int[]} forms.
 */
public final class Geometry{
    /** Cardinal directions, counter-clockwise from +x. */
    public static final List<Point2> d4 = List.of(new Point2(1, 0), new Point2(0, 1), new Point2(-1, 0), new Point2(0, -1));
    /** {@link #d4} plus the center (0, 0). */
    public static final List<Point2> d4c = List.of(
        new Point2(1, 0), new Point2(0, 1), new Point2(-1, 0), new Point2(0, -1), new Point2(0, 0));
    /** The 8 neighbours, counter-clockwise from +x. */
    public static final List<Point2> d8 = List.of(
        new Point2(1, 0), new Point2(1, 1), new Point2(0, 1), new Point2(-1, 1),
        new Point2(-1, 0), new Point2(-1, -1), new Point2(0, -1), new Point2(1, -1));
    /** The 4 diagonals, counter-clockwise from (1, 1). */
    public static final List<Point2> d8edge = List.of(new Point2(1, 1), new Point2(-1, 1), new Point2(-1, -1), new Point2(1, -1));

    private static final int[] d4x = {1, 0, -1, 0};
    private static final int[] d4y = {0, 1, 0, -1};

    /** {@code d4[i mod 4]}, so negative rotations work. */
    public static Point2 d4(int i){
        return d4.get(Mathf.mod(i, 4));
    }

    public static int d4x(int i){
        return d4x[Mathf.mod(i, 4)];
    }

    public static int d4y(int i){
        return d4y[Mathf.mod(i, 4)];
    }

    public static Point2 d8(int i){
        return d8.get(Mathf.mod(i, 8));
    }

    public static Point2 d8edge(int i){
        return d8edge.get(Mathf.mod(i, 4));
    }

    /** Calls {@code cons} for every offset within {@code radius} of (x, y), x-major as in Arc. */
    public static void circle(int x, int y, int radius, IntPair cons){
        for(int dx = -radius; dx <= radius; dx++){
            for(int dy = -radius; dy <= radius; dy++){
                if(Mathf.within(dx, dy, radius)){
                    cons.get(dx + x, dy + y);
                }
            }
        }
    }

    /** As {@link #circle(int, int, int, IntPair)}, clipped to a {@code width × height} grid. */
    public static void circle(int x, int y, int width, int height, int radius, IntPair cons){
        for(int dx = Math.max(x - radius, 0); dx <= Math.min(x + radius, width - 1); dx++){
            for(int dy = Math.max(y - radius, 0); dy <= Math.min(y + radius, height - 1); dy++){
                if(Mathf.within(dx, dy, x, y, radius)){
                    cons.get(dx, dy);
                }
            }
        }
    }

    /**
     * Arc's Bresenham walk from (x0, y0) to (x1, y1), both ends included, stepping diagonally when both axes move.
     * Stops early and returns true when {@code cons} returns true.
     */
    public static boolean raycast(int x0f, int y0f, int x1, int y1, Raycaster cons){
        int x0 = x0f;
        int y0 = y0f;
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);

        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;

        int err = dx - dy;
        int e2;
        while(true){

            if(cons.accept(x0, y0)) return true;
            if(x0 == x1 && y0 == y1) return false;

            e2 = 2 * err;
            if(e2 > -dy){
                err = err - dy;
                x0 = x0 + sx;
            }

            if(e2 < dx){
                err = err + dx;
                y0 = y0 + sy;
            }
        }
    }

    @FunctionalInterface
    public interface Raycaster{
        boolean accept(int x, int y);
    }

    @FunctionalInterface
    public interface IntPair{
        void get(int x, int y);
    }

    private Geometry(){}
}
