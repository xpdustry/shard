package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * Entity snapshots, as {@code NetServer.writeEntitySnapshotsAll} and {@code writeEntitySnapshotsTeam} write them
 * ({@code NetServer.java:1153-1219}): every entity of {@code Groups.sync}, in its order, as {@code i32 id}, {@code u8}
 * class id and its {@code writeSync}, batched into {@code entitySnapshot(amount, data)} packets past 800 bytes.
 *
 * <p>Without fog every player gets the same packets ({@link #all}). With fog the world writes them once per team
 * ({@link #team}): entities hidden from the team are left out and their ids sent in one {@code hiddenSnapshot}, after
 * the entity packets.
 *
 * <p>An entity whose kind has no projection yet (world labels arrive with their row) is left out and counted in
 * {@link #skipped()}, rather than failing the world's tick; the client then simply doesn't see it.
 */
public final class EntitySnapshots{
    private final EntityProjector projector;
    /** The unit being written: encoded at once from the live unit, nothing kept (W4.6). */
    private final UnitView unitView = new UnitView();
    private long skipped;

    public EntitySnapshots(EntityProjector projector){
        this.projector = projector;
    }

    /** What one team's players get: the entity packets, then the hidden ids, or null if none is hidden. */
    public record Team(List<Packets.EntitySnapshot> snapshots, Packets.HiddenSnapshot hidden){
    }

    /** {@code writeEntitySnapshotsAll}: for every player, when no entity is hidden. */
    public List<Packets.EntitySnapshot> all(World world, WireContext context){
        return write(world, context, -1, null);
    }

    /** {@code writeEntitySnapshotsTeam}: what {@code team}'s players see. */
    public Team team(World world, int team, WireContext context){
        var hidden = new IntSeq();
        List<Packets.EntitySnapshot> snapshots = write(world, context, team, hidden);
        return new Team(snapshots, hidden.size == 0 ? null : new Packets.HiddenSnapshot(hidden.toArray()));
    }

    /** Entities left out because they have no projection, since this writer was made. */
    public long skipped(){
        return skipped;
    }

    private List<Packets.EntitySnapshot> write(World world, WireContext context, int team, IntSeq hidden){
        EntityGroups groups = EntityGroups.of(world);
        if(groups == null) return List.of();
        ActiveList<Entity> sync = groups.sync();
        var batch = new Batch();
        for(int i = 0; i < sync.size(); i++){
            Entity entity = sync.get(i);
            if(hidden != null && hidden(entity, team)){
                hidden.add(entity.id());
                continue;
            }
            WireEntity projection;
            int classId;
            try{
                classId = EntityProjector.classId(entity);
                projection = projector.project(entity, unitView);
            }catch(IllegalArgumentException e){
                skipped++;
                continue;
            }
            var out = batch.out();
            out.i(entity.id());
            out.b(classId);
            EntityClasses.writeSync(out, classId, projection, context);
            batch.end();
        }
        List<Batch.Chunk> chunks = batch.finish();
        var packets = new ArrayList<Packets.EntitySnapshot>(chunks.size());
        for(Batch.Chunk chunk : chunks) packets.add(new Packets.EntitySnapshot((short)chunk.amount(), chunk.data()));
        return packets;
    }

    /**
     * {@code isSyncHidden(team)}: a unit in fog to the team ({@link Unit#inFogTo}) that isn't shooting
     * ({@code UnitComp.java:219}: shooting reveals it, so its bullets can be seen); other entities, players included,
     * never hide.
     */
    static boolean hidden(Entity entity, int team){
        return entity instanceof Unit unit && !unit.isShooting && unit.inFogTo(team);
    }
}
