package com.xpdustry.shard.common.geom;

/** The parts of Arc's {@code Intersector} the simulation uses, with its exact arithmetic. */
public final class Intersector{
    private Intersector(){
    }

    /**
     * {@code isInRegularPolygon(sides, cx, cy, radius, rotation, x, y)} (Intersector.java:111): whether (x, y) is inside
     * the polygon whose vertex {@code i} is the centre plus {@code trns(i * 360 / sides + rotation, radius)}.
     */
    public static boolean isInRegularPolygon(int sides, float cx, float cy, float radius, float rotation, float x, float y){
        var polygon = new float[sides * 2];
        var s = new Vec2();
        for(int i = 0; i < sides; i++){
            s.trns(i * 360f / sides + rotation, radius);
            polygon[i * 2] = cx + s.x;
            polygon[i * 2 + 1] = cy + s.y;
        }
        return isInPolygon(polygon, 0, polygon.length, x, y);
    }

    /** {@code isInPolygon(polygon, offset, count, x, y)} (Intersector.java:180): the even-odd rule over {@code count} floats from {@code offset}. */
    public static boolean isInPolygon(float[] polygon, int offset, int count, float x, float y){
        boolean oddNodes = false;
        int j = offset + count - 2;
        for(int i = offset, n = j; i <= n; i += 2){
            float yi = polygon[i + 1];
            float yj = polygon[j + 1];
            if((yi < y && yj >= y) || (yj < y && yi >= y)){
                float xi = polygon[i];
                if(xi + (y - yi) / (yj - yi) * (polygon[j] - xi) < x) oddNodes = !oddNodes;
            }
            j = i;
        }
        return oddNodes;
    }
}
