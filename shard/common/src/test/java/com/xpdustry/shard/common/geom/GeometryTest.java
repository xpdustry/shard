package com.xpdustry.shard.common.geom;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Geometry} and {@link Point2} against Arc's. */
class GeometryTest{
    @Test
    void directionTablesMatchArc(){
        assertPoints(arc.math.geom.Geometry.d4, Geometry.d4);
        assertPoints(arc.math.geom.Geometry.d4c, Geometry.d4c);
        assertPoints(arc.math.geom.Geometry.d8, Geometry.d8);
        assertPoints(arc.math.geom.Geometry.d8edge, Geometry.d8edge);
        for(int i = -20; i < 20; i++){
            assertEquals(arc.math.geom.Geometry.d4x(i), Geometry.d4x(i));
            assertEquals(arc.math.geom.Geometry.d4y(i), Geometry.d4y(i));
            assertEquals(arc.math.geom.Geometry.d4(i).x, Geometry.d4(i).x());
            assertEquals(arc.math.geom.Geometry.d8(i).y, Geometry.d8(i).y());
            assertEquals(arc.math.geom.Geometry.d8edge(i).x, Geometry.d8edge(i).x());
        }
    }

    @Test
    void circlesVisitTheSameTilesInTheSameOrder(){
        var random = new SplittableRandom(7);
        for(int i = 0; i < 2000; i++){
            int x = random.nextInt(-50, 50), y = random.nextInt(-50, 50), r = random.nextInt(0, 12);
            int w = random.nextInt(1, 60), h = random.nextInt(1, 60);
            var ours = new ArrayList<Integer>();
            var theirs = new ArrayList<Integer>();
            Geometry.circle(x, y, r, (cx, cy) -> ours.add(Point2.pack(cx, cy)));
            arc.math.geom.Geometry.circle(x, y, r, (cx, cy) -> theirs.add(Point2.pack(cx, cy)));
            assertEquals(theirs, ours);
            ours.clear();
            theirs.clear();
            Geometry.circle(x, y, w, h, r, (cx, cy) -> ours.add(Point2.pack(cx, cy)));
            arc.math.geom.Geometry.circle(x, y, w, h, r, (cx, cy) -> theirs.add(Point2.pack(cx, cy)));
            assertEquals(theirs, ours);
        }
    }

    @Test
    void raycastsWalkTheSameLine(){
        var random = new SplittableRandom(8);
        for(int i = 0; i < 20_000; i++){
            int x0 = random.nextInt(-40, 40), y0 = random.nextInt(-40, 40), x1 = random.nextInt(-40, 40), y1 = random.nextInt(-40, 40);
            int stopAfter = random.nextInt(0, 100);
            var ours = new ArrayList<Integer>();
            var theirs = new ArrayList<Integer>();
            boolean a = Geometry.raycast(x0, y0, x1, y1, (x, y) -> {
                ours.add(Point2.pack(x, y));
                return ours.size() > stopAfter;
            });
            boolean b = arc.math.geom.Geometry.raycast(x0, y0, x1, y1, (x, y) -> {
                theirs.add(Point2.pack(x, y));
                return theirs.size() > stopAfter;
            });
            assertEquals(b, a);
            assertEquals(theirs, ours);
        }
    }

    @Test
    void pointPackingMatchesArcOnEveryShortPair(){
        var random = new SplittableRandom(9);
        for(int i = 0; i < 1_000_000; i++){
            int x = i < 1000 ? i - 500 : random.nextInt(), y = i < 1000 ? 500 - i : random.nextInt();
            int pos = Point2.pack(x, y);
            assertEquals(arc.math.geom.Point2.pack(x, y), pos);
            assertEquals(arc.math.geom.Point2.x(pos), Point2.x(pos));
            assertEquals(arc.math.geom.Point2.y(pos), Point2.y(pos));
            var unpacked = arc.math.geom.Point2.unpack(pos);
            assertEquals(new Point2(unpacked.x, unpacked.y), Point2.unpack(pos));
        }
    }

    @Test
    void pointArithmeticMatchesArc(){
        var random = new SplittableRandom(10);
        for(int i = 0; i < 100_000; i++){
            int x = random.nextInt(-1000, 1000), y = random.nextInt(-1000, 1000), steps = random.nextInt(-9, 10);
            int ox = random.nextInt(-1000, 1000), oy = random.nextInt(-1000, 1000);
            var ours = new Point2(x, y);
            assertEquals(new arc.math.geom.Point2(x, y).pack(), ours.pack());
            var rotated = new arc.math.geom.Point2(x, y).rotate(steps);
            assertEquals(new Point2(rotated.x, rotated.y), ours.rotate(steps), "rotate " + steps);
            var added = new arc.math.geom.Point2(x, y).add(ox, oy);
            assertEquals(new Point2(added.x, added.y), ours.add(ox, oy));
            assertEquals(Float.floatToIntBits(new arc.math.geom.Point2(x, y).dst2(ox, oy)), Float.floatToIntBits(ours.dst2(ox, oy)));
            assertEquals(Float.floatToIntBits(new arc.math.geom.Point2(x, y).dst(ox, oy)), Float.floatToIntBits(ours.dst(ox, oy)));
        }
    }

    static void assertPoints(arc.math.geom.Point2[] expected, List<Point2> actual){
        assertEquals(expected.length, actual.size());
        for(int i = 0; i < expected.length; i++) assertEquals(new Point2(expected[i].x, expected[i].y), actual.get(i));
    }
}
