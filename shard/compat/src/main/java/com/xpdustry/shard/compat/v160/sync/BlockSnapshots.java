package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * {@code NetServer.writeBlockSnapshots} ({@code NetServer.java:1103}): for each present team, its buildings flagged
 * {@code synced} in the indexer's order, each as {@code i32 pos}, {@code i16} block id and {@code writeSync}, which is
 * {@code writeAll}: the base and the kind's fields, without the version byte a map chunk starts with (the client reads
 * with its own {@code version()}). Batched past 800 bytes into {@code blockSnapshot(amount, data)}.
 *
 * <p>A {@link PassthroughBuilding} is left out: its bytes are the map's, frozen when it loaded, and Shard doesn't
 * simulate it, so sending them would wind the client's copy back to the load every 6 s.
 */
public final class BlockSnapshots{
    private final ContentEpoch content;
    private final BuildingCodecs codecs;
    private final ContentHeader.IdMapping ids;

    /** Writes worlds of {@code content}; sync carries its ids as they are. */
    public BlockSnapshots(ContentEpoch content, BuildingCodecs codecs){
        this.content = content;
        this.codecs = codecs;
        this.ids = ContentHeader.of(content).mapping(content);
    }

    public List<Packets.BlockSnapshot> write(World world){
        var batch = new Batch();
        IntSeq teams = TeamPresence.present(world);
        for(int t = 0; t < teams.size; t++){
            for(Building building : world.buildings().flagged(teams.get(t), BlockFlag.synced)){
                if(building instanceof PassthroughBuilding) continue;
                entry(batch, building);
            }
        }
        List<Batch.Chunk> chunks = batch.finish();
        var packets = new ArrayList<Packets.BlockSnapshot>(chunks.size());
        for(Batch.Chunk chunk : chunks) packets.add(new Packets.BlockSnapshot((short)chunk.amount(), chunk.data()));
        return packets;
    }

    /** {@code NetServer.syncBuilding}'s body for one building: what {@code requestBlockSnapshot} answers. */
    public Packets.BlockSnapshot one(Building building){
        var batch = new Batch();
        entry(batch, building);
        Batch.Chunk chunk = batch.finish().getFirst();
        return new Packets.BlockSnapshot((short)chunk.amount(), chunk.data());
    }

    private void entry(Batch batch, Building building){
        byte[] chunk = codecs.chunk(building, content, ids);
        var out = batch.out();
        out.i(building.pos());
        out.s(building.block().id());
        out.b(chunk, 1, chunk.length - 1);
        batch.end();
    }
}
