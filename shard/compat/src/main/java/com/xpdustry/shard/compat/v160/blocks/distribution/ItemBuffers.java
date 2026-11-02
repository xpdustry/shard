package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;

/**
 * {@code DirectionalItemBuffer.read}/{@code write}: per direction, the queued count and the capacity as bytes, then
 * every slot as a long. Old sorters and overflow gates saved one; they read it into a throwaway.
 */
final class ItemBuffers{
    private ItemBuffers(){
    }

    /** Reads past a saved buffer: {@code new DirectionalItemBuffer(n).read(read)}, whose values nothing keeps. */
    static void skip(Reads in){
        for(int i = 0; i < 4; i++){
            in.b();
            byte length = in.b();
            for(int j = 0; j < length; j++) in.l();
        }
    }

    static void skip(Tape in){
        for(int i = 0; i < 4; i++){
            in.b();
            byte length = in.b();
            for(int j = 0; j < length; j++) in.l();
        }
    }
}
