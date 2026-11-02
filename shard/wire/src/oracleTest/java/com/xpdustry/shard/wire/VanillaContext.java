package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.Reads;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.payloads.*;

import java.io.*;

import static mindustry.Vars.*;

/**
 * {@link WireContext} over vanilla's loaded content and world, so Shard's decoders see what vanilla's do. Block payload
 * bodies are read by vanilla itself: set {@link #source} to the array the {@code Reads} reads first.
 */
final class VanillaContext implements WireContext{
    /** The bytes the current {@code Reads} reads, from position 0, for {@link #readBuildingBody}. */
    byte[] source;

    VanillaContext source(byte[] bytes){
        source = bytes;
        return this;
    }

    @Override
    public boolean statusDynamic(int status){
        return content.getByID(ContentType.status, status) instanceof StatusEffect s && s.dynamic;
    }

    @Override
    public boolean contentExists(int type, int id){
        return content.getByID(ContentType.all[type], id) != null;
    }

    @Override
    public boolean blockRotates(int block){
        Block b = content.block(block);
        return b != null && b.rotate;
    }

    @Override
    public boolean blockExists(int block){
        return content.block(block) != null;
    }

    @Override
    public boolean unlockable(int type, int id){
        return content.getByID(ContentType.all[type], id) instanceof UnlockableContent;
    }

    @Override
    public boolean tileExists(int pos){
        return world.tile(pos) != null;
    }

    @Override
    public int syncedClass(int id){
        var entity = mindustry.gen.Groups.sync.getByID(id);
        return entity == null ? -1 : entity.classId() & 0xFF;
    }

    @Override
    public byte[] readBuildingBody(Reads read, int block, int version){
        Block type = content.block(block);
        if(type == null) throw new WireException("unknown block " + block);
        var input = new ByteArrayInputStream(source, read.position(), source.length - read.position());
        int before = input.available();
        new BuildPayload(type, Team.derelict).build.readAll(new arc.util.io.Reads(new DataInputStream(input)), (byte)version);
        return read.b(before - input.available());
    }
}
