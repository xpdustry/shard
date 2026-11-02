package com.xpdustry.shard.sim.blocks.logic;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code CanvasBlock.CanvasBuild} (CanvasBlock.java): the canvas and the large canvas, a
 * {@code canvasSize}² image of palette indices packed {@code bitsPerPixel} bits each. A {@code byte[]} config of the
 * right length replaces the image; processors read and write pixels by index. {@code blending} records which sides
 * touch another canvas of the same block origin to origin (drawing only, kept as vanilla).
 */
@Kind("canvas-block")
public final class CanvasBlock extends Building implements Checkpointable, LogicReadable, LogicWritable{
    static final Configs<CanvasBlock> CONFIGS = Configs.of(CanvasBlock.class).on(byte[].class, (build, bytes) -> {
        if(build.data.length == bytes.length) System.arraycopy(bytes, 0, build.data, 0, bytes.length);
    });

    public byte[] data = {};
    public int blending;
    @Derived("canvasSize, palette size and bitsPerPixel, read in created() and on first use")
    private int canvasSize = -1, colors, bits;

    @Override
    public void created(){
        params();
        data = new byte[Mathf.ceil(canvasSize * canvasSize * bits / 8f)];
    }

    private void params(){
        if(canvasSize != -1) return;
        if(block().baked()){
            canvasSize = block().fields().i("canvasSize");
            colors = block().fields().list("palette").size();
        }else{
            canvasSize = 8;
            colors = 8;
        }
        bits = Mathf.log2(Mathf.nextPowerOfTwo(colors));
    }

    /** {@code setPixel(pos, index)}. */
    public void setPixel(int pos, int index){
        params();
        if(pos < canvasSize * canvasSize && pos >= 0 && index >= 0 && index < colors) setByte(data, pos * bits, index);
    }

    /** {@code getPixel(pos)}: the palette index, or NaN out of range. */
    public double getPixel(int pos){
        params();
        if(pos >= 0 && pos < canvasSize * canvasSize) return getByte(data, pos * bits);
        return Double.NaN;
    }

    private int getByte(byte[] data, int bitOffset){
        int result = 0;
        for(int i = 0; i < bits; i++){
            int word = i + bitOffset >>> 3;
            result |= ((data[word] & (1 << (i + bitOffset & 7))) == 0 ? 0 : 1) << i;
        }
        return result;
    }

    private void setByte(byte[] bytes, int bitOffset, int value){
        for(int i = 0; i < bits; i++){
            int word = i + bitOffset >>> 3;
            if(((value >>> i) & 1) == 0){
                bytes[word] &= (byte)~(1 << (i + bitOffset & 7));
            }else{
                bytes[word] |= (byte)(1 << (i + bitOffset & 7));
            }
        }
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        blending = 0;
        TileGrid tiles = world().tiles();
        int size = block().size();
        for(int i = 0; i < 4; i++){
            int x = tileX() + com.xpdustry.shard.common.geom.Geometry.d4x(i) * size, y = tileY() + com.xpdustry.shard.common.geom.Geometry.d4y(i) * size;
            if(!tiles.inBounds(x, y)) continue;
            Building other = world().buildings().at(x, y);
            if(other != null && other.block() == block() && other.tileX() == x && other.tileY() == y) blending |= 1 << i;
        }
    }

    @Override
    public double sense(LAccess sensor){
        params();
        if(sensor == LAccess.displayWidth || sensor == LAccess.displayHeight) return canvasSize;
        return super.sense(sensor);
    }

    @Override
    public boolean readable(Processor exec){
        return isValid() && (exec.privileged || team == exec.team());
    }

    @Override
    public void read(Processor exec, int position, int output){
        exec.setnum(output, getPixel(exec.numi(position)));
    }

    @Override
    public boolean writable(Processor exec){
        return readable(exec);
    }

    @Override
    public void write(Processor exec, int position, int value){
        setPixel(exec.numi(position), exec.numi(value));
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    /** {@code config()}: the image itself, as vanilla hands it out. */
    @Override
    public Object config(){
        return data;
    }

    @Override
    public void write(CheckpointOut out){
        out.bytes("data", data).i("blending", blending);
    }

    @Override
    public void read(CheckpointIn in){
        data = in.bytes("data");
        blending = in.i("blending");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (CanvasBlock)into;
        out.data = data.clone();
        out.blending = blending;
        out.canvasSize = canvasSize;
        out.colors = colors;
        out.bits = bits;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bytes(data).i(blending);
    }

    @Override
    public void afterPickedUp(){
        super.afterPickedUp();
        blending = 0;
    }

    /** {@code dropped()}: placed again, it blends with its new neighbours. */
    @Override
    public void dropped(){
        super.dropped();
        onProximityUpdate();
    }
}
