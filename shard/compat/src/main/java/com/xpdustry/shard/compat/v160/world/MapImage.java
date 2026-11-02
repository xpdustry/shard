package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.blocks.environment.*;

import java.util.*;

/**
 * A map section, frozen (vanilla {@code SaveVersion.writeMap}): tiles in a content header's ids, which tiles save
 * data, and each building's chunk at its centre.
 *
 * @param data by position, for the tiles that save data: {@code Tile.getPackedData()} ({@link TileDataCodec#pack})
 * @param centers per tile, the position of the centre of the building covering it, or -1
 * @param chunks by centre position: the chunk after its length (revision byte, then {@code writeAll})
 */
public record MapImage(int width, int height, short[] floors, short[] overlays, short[] blocks, BitSet saveData,
                       SortedMap<Integer, Long> data, int[] centers, SortedMap<Integer, byte[]> chunks){

    /** Vanilla's {@code writeMap}: run-length floors and overlays, then blocks with their data and building chunks. */
    public void write(Writes out){
        int size = width * height;
        out.s(width);
        out.s(height);
        for(int i = 0; i < size; i++){
            out.s(floors[i]);
            out.s(overlays[i]);
            int run = 0;
            for(int j = i + 1; j < size && run < 255; j++){
                if(floors[j] != floors[i] || overlays[j] != overlays[i]) break;
                run++;
            }
            out.b(run);
            i += run;
        }
        for(int i = 0; i < size; i++){
            out.s(blocks[i]);
            boolean save = saveData.get(i), build = centers[i] >= 0;
            out.b((build ? 1 : 0) | (save ? 4 : 0));
            if(save){
                TileDataCodec.write(out, data.getOrDefault(i, 0L));
            }
            if(build){
                if(centers[i] == i){
                    byte[] chunk = chunks.get(i);
                    out.bool(true);
                    out.i(chunk.length);
                    out.b(chunk);
                }else{
                    out.bool(false);
                }
            }else if(!save){
                // Tiles with data never start or continue a run.
                int run = 0;
                for(int j = i + 1; j < size && run < 255; j++){
                    if(blocks[j] != blocks[i] || saveData.get(j)) break;
                    run++;
                }
                out.b(run);
                i += run;
            }
        }
    }
}
