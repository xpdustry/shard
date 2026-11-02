package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;

/** {@code MessageBuild.write}/{@code read}: the text, as {@code writeUTF}. */
@Register
public final class MessageBlockCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("message-block");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 0;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        out.str(((MessageBlock)building).message.toString());
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var message = ((MessageBlock)building).message;
        message.setLength(0);
        message.append(in.str());
    }

    @Override
    public void skip(Tape in, int revision){
        in.str();
    }
}
