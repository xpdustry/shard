package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * {@link WireContext} for one world, frozen at a tick boundary ({@link #capture}) so encoders and decoders on other
 * threads see the world as it was: the content epoch (immutable), the map size, and {@code Groups.sync}'s ids with
 * their entity classes. Content ids are the epoch's, which are the client's.
 *
 * <p>{@link #unlockable}: vanilla's {@code UnlockableContent} kinds are items, blocks, liquids, statuses, units,
 * weathers, sector presets and planets; bullets, commands and stances are not, and v160.7 registers no team entries.
 * Ids outside a type's list are null to vanilla, so not unlockable either. {@link #readBuildingBody} reads a carried
 * building with its kind's {@link BuildingCodecs codec}; a kind without one fails.
 */
public final class WorldWireContext implements WireContext{
    private static final int SECTOR = ContentType.VANILLA_NAMES.indexOf("sector"), PLANET = ContentType.VANILLA_NAMES.indexOf("planet");

    private final ContentEpoch content;
    private final KindRegistry kinds;
    private final int width, height;
    /** Ascending synced ids and, at the same index, their class ids. */
    private final int[] syncIds, syncClasses;
    private final int[] unlockableCounts, contentCounts;

    private WorldWireContext(ContentEpoch content, KindRegistry kinds, int width, int height, int[] syncIds, int[] syncClasses){
        this.content = content;
        this.kinds = kinds;
        this.width = width;
        this.height = height;
        this.syncIds = syncIds;
        this.syncClasses = syncClasses;
        unlockableCounts = new int[ContentType.VANILLA_NAMES.size()];
        contentCounts = new int[ContentType.VANILLA_NAMES.size()];
        Arrays.fill(contentCounts, -1); //a type Shard doesn't model (teams, commands, stances): accept, as the default does
        for(ContentType type : ContentType.values()){
            contentCounts[type.vanillaOrdinal()] = content.count(type);
            if(type != ContentType.BULLET) unlockableCounts[type.vanillaOrdinal()] = content.count(type);
        }
        unlockableCounts[SECTOR] = contentCounts[SECTOR] = content.sectors().size();
        unlockableCounts[PLANET] = contentCounts[PLANET] = content.planets().size();
    }

    /** The context of {@code world} as it is now. World thread, between ticks. */
    public static WorldWireContext capture(World world){
        EntityGroups groups = EntityGroups.of(world);
        int size = groups == null ? 0 : groups.sync().size();
        long[] pairs = new long[size];
        for(int i = 0; i < size; i++){
            Entity entity = groups.sync().get(i);
            pairs[i] = ((long)entity.id() << 32) | (EntityProjector.classId(entity) & 0xffffffffL);
        }
        Arrays.sort(pairs);
        int[] ids = new int[size], classes = new int[size];
        for(int i = 0; i < size; i++){
            ids[i] = (int)(pairs[i] >> 32);
            classes[i] = (int)pairs[i];
        }
        return new WorldWireContext(world.content(), world.kinds(), world.tiles().width(), world.tiles().height(), ids, classes);
    }

    /**
     * The context of a world built with {@code config}, for decoding client packets on network threads: its content
     * and map size, which never change, and no synced entities, so {@link #syncedClass} answers -1 for every id and
     * a packet's entity references are checked against the live world when its intent is admitted.
     */
    public static WorldWireContext of(WorldConfig config){
        return new WorldWireContext(config.content(), config.kinds(), config.width(), config.height(), new int[0], new int[0]);
    }

    @Override
    public boolean statusDynamic(int status){
        return status >= 0 && status < content.count(ContentType.STATUS) && content.status(status).dynamic();
    }

    @Override
    public boolean contentExists(int type, int id){
        if(type < 0 || type >= contentCounts.length) return false;
        int count = contentCounts[type];
        return count < 0 || id >= 0 && id < count;
    }

    @Override
    public boolean blockRotates(int block){
        return blockExists(block) && content.block(block).rotate();
    }

    @Override
    public boolean blockExists(int block){
        return block >= 0 && block < content.blocks();
    }

    @Override
    public boolean unlockable(int type, int id){
        return type >= 0 && type < unlockableCounts.length && id >= 0 && id < unlockableCounts[type];
    }

    @Override
    public boolean tileExists(int pos){
        int x = Point2.x(pos), y = Point2.y(pos);
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    @Override
    public int syncedClass(int id){
        int index = Arrays.binarySearch(syncIds, id);
        return index < 0 ? -1 : syncClasses[index];
    }

    @Override
    public byte[] readBuildingBody(Reads read, int block, int version){
        if(!blockExists(block)) throw new WireException("block payload of block " + block + ", which does not exist");
        try{
            return BuildingCodecs.builtIn().readBody(read, content.block(block), kinds.key(content.block(block)), version, this);
        }catch(IllegalArgumentException e){
            throw new WireException("block payload of " + content.block(block).name() + ": " + e.getMessage(), e);
        }
    }
}
