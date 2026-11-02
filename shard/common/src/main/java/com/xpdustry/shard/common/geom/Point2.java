package com.xpdustry.shard.common.geom;

/**
 * An immutable grid point, and Arc's {@code Point2} packing: {@code (short)x << 16 | (short)y & 0xFFFF}. Tile
 * positions go on the wire packed this way ({@code Tile.pos()}). Inside a world, a tile is {@code y * width + x}
 * instead; convert at the edge.
 */
public record Point2(int x, int y){
    /** Packs by casting each component to a short, as Arc does: coordinates outside [-32768, 32767] wrap. */
    public static int pack(int x, int y){
        return (((short)x) << 16) | (((short)y) & 0xFFFF);
    }

    public static Point2 unpack(int pos){
        return new Point2(x(pos), y(pos));
    }

    /** The x component of a packed position. */
    public static short x(int pos){
        return (short)(pos >>> 16);
    }

    /** The y component of a packed position. */
    public static short y(int pos){
        return (short)(pos & 0xFFFF);
    }

    public int pack(){
        return pack(x, y);
    }

    public Point2 add(int dx, int dy){
        return new Point2(x + dx, y + dy);
    }

    public Point2 add(Point2 other){
        return new Point2(x + other.x, y + other.y);
    }

    /** Rotates in 90-degree steps, counter-clockwise for positive {@code steps}, as Arc's {@code rotate}. */
    public Point2 rotate(int steps){
        int x = this.x, y = this.y;
        for(int i = 0; i < Math.abs(steps); i++){
            int ox = x;
            if(steps >= 0){
                x = -y;
                y = ox;
            }else{
                x = y;
                y = -ox;
            }
        }
        return new Point2(x, y);
    }

    public float dst2(int x, int y){
        int xd = x - this.x;
        int yd = y - this.y;
        return xd * xd + yd * yd;
    }

    public float dst(int x, int y){
        int xd = x - this.x;
        int yd = y - this.y;
        return (float)Math.sqrt(xd * xd + yd * yd);
    }

    public boolean equals(int x, int y){
        return this.x == x && this.y == y;
    }

    @Override
    public String toString(){
        return "(" + x + ", " + y + ")";
    }
}
