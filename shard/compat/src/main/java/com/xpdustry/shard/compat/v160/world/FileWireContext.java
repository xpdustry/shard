package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * What the save layouts ask of {@code world} while a body in a file's ids ({@code ids}' header) is read: content and
 * tiles are looked up through the mapping, and a carried building's body is measured with its codec. Loaded saves read
 * with it, and world streams in a save's id space write with it.
 */
public record FileWireContext(World world, BuildingCodecs codecs, ContentHeader.IdMapping ids) implements WireContext{
    @Override
    public boolean statusDynamic(int status){
        int id = ids.toCurrent(ContentType.STATUS, status);
        return id >= 0 && id < world.content().count(ContentType.STATUS) && world.content().status(id).dynamic();
    }

    @Override
    public boolean contentExists(int type, int id){
        for(ContentType t : ContentType.values()){
            if(t.vanillaOrdinal() == type) return ids.toCurrent(t, id) >= 0;
        }
        return true;
    }

    @Override
    public boolean blockRotates(int block){
        return blockExists(block) && world.content().block(ids.toCurrent(ContentType.BLOCK, block)).fields().b("rotate");
    }

    @Override
    public boolean blockExists(int block){
        int id = ids.toCurrent(ContentType.BLOCK, block);
        return id >= 0 && id < world.content().blocks();
    }

    @Override
    public boolean unlockable(int type, int id){
        for(ContentType t : List.of(ContentType.ITEM, ContentType.BLOCK, ContentType.LIQUID, ContentType.STATUS, ContentType.UNIT)){
            if(t.vanillaOrdinal() == type) return id >= 0 && id < world.content().count(t);
        }
        return false;
    }

    @Override
    public boolean tileExists(int pos){
        int x = pos >>> 16, y = pos & 0xFFFF;
        return x < world.tiles().width() && y < world.tiles().height();
    }

    @Override
    public int syncedClass(int id){
        return -1;
    }

    @Override
    public byte[] readBuildingBody(Reads read, int block, int version){
        BlockDef def = world.content().block(Math.max(ids.toCurrent(ContentType.BLOCK, block), 0));
        KindKey kind = world.kinds().key(def);
        if(codecs.get(kind) == null) throw new WireException("a block payload of " + def.name() + " inside a unit, whose length only a codec knows");
        return codecs.readBody(read, def, kind, version, this);
    }
}
