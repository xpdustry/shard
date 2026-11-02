package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code UnitAssemblerModule.UnitAssemblerModuleBuild} (UnitAssemblerModule.java:71): a payload block set
 * against a {@link UnitAssembler}'s area, facing its centre, that raises the assembler's tier ({@code tier}) and takes
 * the payloads the assembler needs. Whenever the world's tiles changed since its last update
 * ({@link Buildings#tileChanges()}), it finds its assembler again ({@link #findLink}): the first of its team's
 * assemblers, in {@code indexer.getFlagged} order, that it fits ({@link UnitAssembler#moduleFits}). Powered, it moves a
 * payload to its centre and throws it into the assembler's area, while the area is clear.
 *
 * <p>Neither the link nor {@link #lastChange} is saved: a loaded module finds its assembler on its first update.
 */
@Kind("unit-assembler-module")
public final class UnitAssemblerModule extends PayloadBuild<Payload> implements Checkpointable{
    /** {@code link}: the assembler this module serves, or null. */
    public UnitAssembler link;
    /** {@code lastChange}: {@code world.tileChanges} at the last {@link #findLink}. */
    public int lastChange = -2;

    @Derived("the block's tier, read on first use")
    private int tier = -1;

    /** {@code tier()}. */
    public int tier(){
        if(tier < 0) tier = block().baked() ? block().fields().i("tier") : 1;
        return tier;
    }

    /** {@code getLink(team, x, y, rotation)}: the first assembler of {@code team} a module of {@code block} at (x, y) fits. */
    public static UnitAssembler link(World world, BlockDef block, int team, int x, int y, int rotation){
        float offset = UnitAssembler.offset(block);
        for(Building b : world.buildings().flagged(team, BlockFlag.unitAssembler)){
            if(b instanceof UnitAssembler assembler && assembler.moduleFits(block, x * Footprint.TILE_SIZE + offset, y * Footprint.TILE_SIZE + offset, rotation)){
                return assembler;
            }
        }
        return null;
    }

    /** {@code canPlaceOn(tile, team, rotation)}: only where it links to an assembler. */
    public static boolean canPlaceOn(World world, BlockDef block, int x, int y, int team, int rotation){
        return link(world, block, team, x, y, rotation) != null;
    }

    /** {@code findLink()}. */
    public void findLink(){
        if(link != null){
            link.removeModule(this);
        }
        link = link(world(), block(), team, tileX(), tileY(), rotation);
        if(link != null){
            link.updateModules(this);
        }
    }

    @Override
    public boolean acceptPayload(Building source, Payload payload){
        return link != null && this.payload == null && link.acceptPayload(this, payload);
    }

    @Override
    public void onRemoved(){
        super.onRemoved();
        if(link != null){
            link.removeModule(this);
        }
    }

    @Override
    public void updateTile(){
        int changes = world().buildings().tileChanges();
        if(lastChange != changes){
            lastChange = changes;
            findLink();
        }

        if(moveInPayload() && link != null && link.moduleFits(block(), x(), y(), rotation) && !link.wasOccupied && link.acceptPayload(this, payload) && efficiency > 0){
            link.yeetPayload(payload);
            payload = null;
        }
    }

    // state

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (UnitAssemblerModule)into;
        out.link = context.map(link);
        out.lastChange = lastChange;
        out.tier = tier;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(link == null ? -1 : link.id()).i(lastChange);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.ref("link", link).i("lastChange", lastChange);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        link = in.ref("link", UnitAssembler.class);
        lastChange = in.i("lastChange");
    }
}
