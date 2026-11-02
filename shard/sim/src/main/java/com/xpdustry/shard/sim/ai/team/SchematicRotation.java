package com.xpdustry.shard.sim.ai.team;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.ai.team.BaseRegistry.*;
import com.xpdustry.shard.sim.tile.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Vanilla's {@code Schematics.rotate(schematic, times)} (Schematics.java:712) for a base part: each quarter turn moves
 * every tile around the schematic's middle, centred on its multiblock position, turns its rotation, and turns the
 * points in its config ({@code BuildPlan.pointConfig}, with {@code LogicBlock.pointConfig} for processors' links).
 */
public final class SchematicRotation{
    static final KindKey LOGIC_BLOCK = KindKey.of("logic-block");
    // LogicBlock.java:36
    private static final int MAX_BYTE_LEN = 1024 * 100, MAX_COMPRESSED_LEN = 16_000, MAX_LINKS = 6000;

    private SchematicRotation(){
    }

    /** A rotated schematic: its size and tiles. */
    public record Rotated(int width, int height, List<Stile> tiles){
    }

    /** {@code rotate(part.schematic, times)}: counter-clockwise for positive {@code times}. */
    public static Rotated rotate(BasePart part, int times){
        var out = new Rotated(part.width(), part.height(), part.tiles());
        boolean sign = times > 0;
        for(int i = 0; i < Math.abs(times); i++) out = rotated(out, sign);
        return out;
    }

    /** {@code rotated(input, counter)}. */
    private static Rotated rotated(Rotated input, boolean counter){
        int direction = Mathf.sign(counter);
        int ox = input.width / 2, oy = input.height / 2;
        var tiles = new ArrayList<Stile>(input.tiles.size());
        for(Stile req : input.tiles){
            Object config = pointConfig(req.block(), req.config(), direction);
            // rotate actual plan, centered on its multiblock position
            float offset = Footprint.worldOffset(req.block().size());
            float wx = (req.x() - ox) * 8 + offset, wy = (req.y() - oy) * 8 + offset;
            float x = wx;
            if(direction >= 0){
                wx = -wy;
                wy = x;
            }else{
                wx = wy;
                wy = -x;
            }
            int nx = (short)(Math.round((wx - offset) / 8f) + ox);
            int ny = (short)(Math.round((wy - offset) / 8f) + oy);
            int rotation = (byte)Mathf.mod(req.rotation() + direction, 4);
            tiles.add(new Stile(req.block(), nx, ny, config, rotation));
        }
        // assign flipped values, since it's rotated
        return new Rotated(input.height, input.width, List.copyOf(tiles));
    }

    /** One quarter turn of a relative point. */
    private static Point2 turn(int x, int y, int direction){
        return direction >= 0 ? new Point2(-y, x) : new Point2(y, -x);
    }

    /** {@code BuildPlan.pointConfig(block, config, cons)} with a quarter turn. */
    static Object pointConfig(BlockDef block, Object config, int direction){
        if(config instanceof Point2 point){
            return turn(point.x(), point.y(), direction);
        }else if(config instanceof Point2[] points){
            var result = new Point2[points.length];
            for(int i = 0; i < points.length; i++) result[i] = turn(points[i].x(), points[i].y(), direction);
            return result;
        }else if(block != null && block.isA(LOGIC_BLOCK)){
            return logicPointConfig(config, direction);
        }
        return config;
    }

    /**
     * {@code LogicBlock.pointConfig(config, transformer)} (LogicBlock.java:192): the links of a compressed config turned,
     * the code bytes kept as they are, compressed again ({@code LogicBlock.compress}); a config that fails to read is
     * kept.
     */
    private static Object logicPointConfig(Object config, int direction){
        if(!(config instanceof byte[] data) || data.length > MAX_COMPRESSED_LEN) return config;
        try(var stream = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))){
            // discard version for now
            stream.read();
            int bytelen = stream.readInt();
            if(bytelen > MAX_BYTE_LEN) throw new IllegalStateException("Logic data too long or malformed! Length: " + bytelen);
            byte[] bytes = new byte[bytelen];
            stream.readFully(bytes);
            int total = Math.min(stream.readInt(), MAX_LINKS);
            var names = new ArrayList<String>(Math.max(total, 0));
            var xs = new int[Math.max(total, 0)];
            var ys = new int[Math.max(total, 0)];
            for(int i = 0; i < total; i++){
                String name = stream.readUTF();
                short x = stream.readShort(), y = stream.readShort();
                Point2 turned = turn(x, y, direction);
                names.add(name);
                xs[i] = turned.x();
                ys[i] = turned.y();
            }
            return compress(bytes, names, xs, ys);
        }catch(IOException ignored){
            // error should not be logged
        }
        return config;
    }

    /** {@code LogicBlock.compress(bytes, links)}. */
    private static byte[] compress(byte[] bytes, List<String> names, int[] xs, int[] ys) throws IOException{
        var baos = new ByteArrayOutputStream();
        var stream = new DataOutputStream(new DeflaterOutputStream(baos));
        // current version of config format
        stream.write(1);
        // write string data
        stream.writeInt(bytes.length);
        stream.write(bytes);
        stream.writeInt(names.size());
        for(int i = 0; i < names.size(); i++){
            stream.writeUTF(names.get(i));
            stream.writeShort(xs[i]);
            stream.writeShort(ys[i]);
        }
        stream.close();
        return baos.toByteArray();
    }
}
