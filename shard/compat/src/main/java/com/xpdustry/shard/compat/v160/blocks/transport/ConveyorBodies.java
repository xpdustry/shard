package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.transport.*;

/**
 * {@code ConveyorBuild.write}/{@code read} (Conveyor.java:392-433), shared by the conveyor and armored conveyor
 * codecs: the item count, then per item its id as a short and its x and y as a byte each (revision 0: one int per
 * item, the id in its top byte). Reading keeps the first three, then runs the conveyor's read-time update.
 */
final class ConveyorBodies{
    /** Vanilla's {@code version()} of {@code ConveyorBuild}. */
    static final int REVISION = 1;

    private ConveyorBodies(){
    }

    static void write(ConveyorBuild conveyor, Writes out, ContentHeader.IdMapping ids){
        out.i(conveyor.len);
        for(int i = 0; i < conveyor.len; i++){
            out.s(ids.requireStream(ContentType.ITEM, conveyor.ids[i]));
            out.b((byte)(conveyor.xs[i] * 127));
            out.b((byte)(conveyor.ys[i] * 255 - 128));
        }
    }

    static void read(ConveyorBuild conveyor, Reads in, int revision, ContentHeader.IdMapping ids){
        int amount = in.i();
        conveyor.len = Math.min(amount, conveyor.ids.length);
        for(int i = 0; i < amount; i++){
            int id;
            float x, y;
            if(revision == 0){
                int val = in.i();
                id = ((byte)(val >> 24)) & 0xff;
                x = (float)((byte)(val >> 16)) / 127f;
                y = ((float)((byte)(val >> 8)) + 128f) / 255f;
            }else{
                id = in.s();
                x = (float)in.b() / 127f;
                y = ((float)in.b() + 128f) / 255f;
            }
            if(i < conveyor.ids.length){
                conveyor.ids[i] = ids.toCurrent(ContentType.ITEM, id);
                conveyor.xs[i] = x;
                conveyor.ys[i] = y;
            }
        }
        // Conveyor.java:432: "this updates some state", once, before the map has proximity
        conveyor.loaded();
    }

    static void skip(Tape in, int revision){
        int amount = in.i();
        for(int i = 0; i < amount; i++){
            if(revision == 0){
                in.i();
            }else{
                in.s();
                in.b();
                in.b();
            }
        }
    }
}
