package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.sandbox.LaunchPayload;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * Brings a map's saved fires, puddles and launch payloads to life, as vanilla's {@code readWorldEntities} does: {@code Fire.create()} or
 * {@code Puddle.create()} (from the pool, or new with the constructor's draws and entity id), then {@code read()},
 * whose {@code afterRead()} maps the tile to it. The caller gives it its saved id and adds it. Bodies of any revision
 * are read in the file's ids; a puddle's liquid moves to the epoch's (null, -1, when the epoch lacks it, which removes
 * the puddle on its first update, as vanilla's {@code getByID} does).
 */
final class SavedHazards{
    private final World world;
    private final ContentHeader.IdMapping ids;
    private final WireContext source;
    private final boolean present;

    SavedHazards(World world, WireContext source, ContentHeader.IdMapping ids){
        this.world = world;
        this.ids = ids;
        this.source = source;
        boolean fires = false, puddles = false;
        for(WorldPart part : world.parts()){
            fires |= part instanceof Fires;
            puddles |= part instanceof Puddles;
        }
        present = fires && puddles && EntityGroups.of(world) != null;
    }

    /** Whether {@code classId} is a fire, a puddle or a launch payload this reader brings to life. */
    boolean handles(int classId){
        return present && (classId == EntityClasses.FIRE || classId == EntityClasses.PUDDLE || classId == EntityClasses.LAUNCH_PAYLOAD);
    }

    /**
     * The fire or puddle saved in {@code body} (its revision first), created, read and registered but not added, with the
     * entity id it drew; or null when the body can't be read, and then nothing was drawn.
     */
    Entity read(int classId, byte[] body){
        WireEntity saved;
        try{
            saved = EntityClasses.readSave(new Reads(body), classId, source);
        }catch(WireException | IndexOutOfBoundsException e){
            return null;
        }
        return switch(saved){
            case FireLayout.State state -> fire(state);
            case PuddleLayout.State state -> puddle(state);
            case LaunchPayloadLayout.State state -> payload(state);
            default -> null;
        };
    }

    private Fire fire(FireLayout.State saved){
        Fire fire = Fire.obtain(world);
        fire.lifetime = saved.lifetime;
        fire.tile = tile(saved.tile);
        fire.time = saved.time;
        fire.x = saved.x;
        fire.y = saved.y;
        // afterRead(): Fires.register
        Fires.of(world).register(world, fire);
        return fire;
    }

    private Puddle puddle(PuddleLayout.State saved){
        Puddle puddle = Puddle.obtain(world);
        puddle.amount = saved.amount;
        puddle.liquid = saved.liquid < 0 ? -1 : ids.toCurrent(ContentType.LIQUID, saved.liquid);
        puddle.tile = tile(saved.tile);
        puddle.x = saved.x;
        puddle.y = saved.y;
        // afterRead(): Puddles.register, which vanilla can't do without a tile
        if(puddle.tile != -1) Puddles.of(world).register(world, puddle);
        return puddle;
    }

    /** {@code LaunchPayload.create()} (its id; nothing drawn), then {@code read()}: items move to the epoch's ids, unknown ones to -1. */
    private LaunchPayload payload(LaunchPayloadLayout.State saved){
        LaunchPayload payload = LaunchPayload.create(world);
        payload.lifetime = saved.lifetime;
        var stacks = new int[saved.stacks.size() * 2];
        for(int i = 0; i < saved.stacks.size(); i++){
            stacks[i * 2] = ids.toCurrent(ContentType.ITEM, saved.stacks.get(i).item());
            stacks[i * 2 + 1] = saved.stacks.get(i).amount();
        }
        payload.stacks = stacks;
        payload.team = saved.team;
        payload.time = saved.time;
        payload.x = saved.x;
        payload.y = saved.y;
        return payload;
    }

    /** {@code TypeIO.readTile}: the tile at a packed position, or -1 outside the map. */
    private int tile(int packed){
        return world.tiles().fromWire(packed);
    }
}
