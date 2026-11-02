package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

import java.util.*;

/**
 * The common building state every chunk starts with: vanilla v160.7's {@code Building.writeBase} and
 * {@code readBase} (BuildingComp.java:184-282) with the item, power and liquid modules, over {@link Building}'s fields.
 * Content ids go through the chunk's {@link ContentHeader.IdMapping}.
 *
 * <p>{@code lastDisabler} is stored as a position and read as vanilla's {@code world.build(pos)}: the building there
 * when the chunk is read, so one later in the file is lost, as in vanilla. It is written while it is still valid.
 * Version 4 adds {@code visibleFlags}, written when the rules have fog and some team has seen the building.
 */
public final class BuildingBase{
    /** The version {@link #write} produces without fog visibility. */
    public static final int VERSION = 3;
    /** The version {@link #write} produces with fog visibility. */
    public static final int VERSION_VISIBILITY = 4;

    private BuildingBase(){
    }

    /** The base as read, before it is applied: the loader creates the building with its team and rotation. */
    public record Header(float health, int rotation, int team, boolean modern){
    }

    /** Reads the base's first fields: health, the rotation byte (high bit: a versioned base) and the team. */
    public static Header header(Reads in){
        float health = in.f();
        byte rot = in.b();
        int team = in.b() & 0xff;
        return new Header(health, rot & 0x7f, team, (rot & 0x80) != 0);
    }

    /**
     * Reads the rest of the base into {@code building}, which was just created with {@code header}'s team and
     * rotation; {@code placed} is vanilla's {@code world.build(pos)} while the map is loading.
     */
    public static void read(Building building, Header header, Reads in, ContentHeader.IdMapping ids, PlacedAt placed){
        BlockDef block = building.block();
        building.health = Math.min(header.health(), block.health());
        building.rotation = header.rotation();
        int moduleBits = moduleBitmask(building);
        boolean legacy = true;
        int version = 0;
        if(header.modern()){
            version = in.b();
            if(version >= 1) building.enabled = in.b() == 1;
            if(version >= 2) moduleBits = in.ub();
            legacy = false;
        }
        // a module the block doesn't have is read into a throwaway, as vanilla's `new ItemModule()`
        if((moduleBits & 1) != 0) readItems(building.items != null ? building.items : new ItemModule(building.world().content().items()), in, legacy, ids);
        if((moduleBits & 2) != 0){
            PowerModule power = building.power;
            if(power == null){
                // Vanilla quirk: a throwaway power module still makes a power graph, whose updater draws an entity id
                power = new PowerModule();
                building.world().ids().next();
            }
            readPower(power, in);
        }
        if((moduleBits & 4) != 0) readLiquids(building.liquids != null ? building.liquids : new LiquidModule(building.world().content().liquids()), in, legacy, ids);
        if((moduleBits & 16) != 0){
            building.timeScale = in.f();
            building.timeScaleDuration = in.f();
        }
        if((moduleBits & 32) != 0) building.lastDisabler = placed.at(in.i());
        if(version <= 2) in.bool();
        if(version >= 3){
            building.efficiency = building.potentialEfficiency = in.ub() / 255f;
            building.optionalEfficiency = in.ub() / 255f;
        }
        if(version == 4) building.visibleFlags = in.l();
    }

    /**
     * Writes {@code building}'s base with content ids in {@code ids}' stream ids: {@link #VERSION_VISIBILITY} when the
     * world's rules have fog and {@code visibleFlags} is set, else {@link #VERSION}.
     */
    public static void write(Building building, Writes out, ContentHeader.IdMapping ids){
        boolean writeVisibility = building.rules().fog && building.visibleFlags != 0;
        out.f(building.health);
        out.b(building.rotation | 0x80);
        out.b(building.team);
        out.b(writeVisibility ? VERSION_VISIBILITY : VERSION);
        out.b(building.enabled ? 1 : 0);
        out.b(moduleBitmask(building));
        if(building.items != null) writeItems(building.items, out, ids);
        if(building.power != null) writePower(building.power, out);
        if(building.liquids != null) writeLiquids(building.liquids, out, ids);
        if(building.timeScale != 1f){
            out.f(building.timeScale);
            out.f(building.timeScaleDuration);
        }
        if(hasDisabler(building)) out.i(building.lastDisabler.pos());
        out.b((byte)(Mathf.clamp(building.efficiency) * 255f));
        out.b((byte)(Mathf.clamp(building.optionalEfficiency) * 255f));
        if(writeVisibility) out.l(building.visibleFlags);
    }

    /**
     * Reads past a base on a {@link Tape}, for a building of {@code block} inside a block payload. Bases before version
     * 2 hold the modules a new building of the block has, as vanilla's {@code create(block, Team.derelict)} makes them;
     * unversioned ones also have byte-sized item and liquid ids.
     */
    public static void skip(Tape in, BlockDef block){
        in.f();
        int rot = in.b();
        in.b();
        // moduleBitmask() of a new building: no time scale, no disabler
        int moduleBits = (block.hasItems() ? 1 : 0) | (block.hasPower() ? 2 : 0) | (block.hasLiquids() ? 4 : 0) | 8;
        boolean legacy = true;
        int version = 0;
        if((rot & 0x80) != 0){
            version = in.b();
            if(version >= 1) in.b();
            if(version >= 2) moduleBits = in.ub();
            legacy = false;
        }
        if((moduleBits & 1) != 0){
            int count = legacy ? in.ub() : in.s();
            for(int i = 0; i < count; i++){
                if(legacy) in.ub();
                else in.s();
                in.i();
            }
        }
        if((moduleBits & 2) != 0){
            int links = in.s();
            for(int i = 0; i < links; i++) in.i();
            in.f();
        }
        if((moduleBits & 4) != 0){
            int count = legacy ? in.ub() : in.s();
            for(int i = 0; i < count; i++){
                if(legacy) in.ub();
                else in.s();
                in.f();
            }
        }
        if((moduleBits & 16) != 0){
            in.f();
            in.f();
        }
        if((moduleBits & 32) != 0) in.i();
        if(version <= 2) in.bool();
        if(version >= 3){
            in.ub();
            in.ub();
        }
        if(version == 4) in.l();
    }

    /** {@code moduleBitmask()}: bit 3 is the old consume module, always set. */
    static int moduleBitmask(Building building){
        return (building.items != null ? 1 : 0) | (building.power != null ? 2 : 0) | (building.liquids != null ? 4 : 0) | 8
            | (building.timeScale != 1f ? 16 : 0) | (hasDisabler(building) ? 32 : 0);
    }

    /** {@code lastDisabler != null && lastDisabler.isValid()}. */
    private static boolean hasDisabler(Building building){
        return building.lastDisabler != null && building.lastDisabler.isValid();
    }

    /** {@code ItemModule.read}: ids through the mapping, unknown ones skipped; {@code total} sums what was kept. */
    static void readItems(ItemModule items, Reads in, boolean legacy, ContentHeader.IdMapping ids){
        Arrays.fill(items.items, 0);
        int count = legacy ? in.ub() : in.s();
        items.total = 0;
        for(int i = 0; i < count; i++){
            int id = ids.toCurrent(ContentType.ITEM, legacy ? in.ub() : in.s());
            int amount = in.i();
            if(id >= 0){
                items.items[id] = amount;
                items.total += amount;
            }
        }
    }

    static void writeItems(ItemModule items, Writes out, ContentHeader.IdMapping ids){
        int amount = 0;
        for(int item : items.items) if(item > 0) amount++;
        out.s(amount);
        for(int id = 0; id < items.items.length; id++){
            if(items.items[id] > 0){
                out.s(ids.requireStream(ContentType.ITEM, id));
                out.i(items.items[id]);
            }
        }
    }

    /** {@code LiquidModule.read}: the current liquid becomes the first one with more than the current one. */
    static void readLiquids(LiquidModule liquids, Reads in, boolean legacy, ContentHeader.IdMapping ids){
        Arrays.fill(liquids.liquids, 0f);
        int count = legacy ? in.ub() : in.s();
        for(int i = 0; i < count; i++){
            int id = ids.toCurrent(ContentType.LIQUID, legacy ? in.ub() : in.s());
            float amount = in.f();
            if(id >= 0){
                liquids.liquids[id] = amount;
                if(amount > liquids.liquids[liquids.current]) liquids.current = id;
            }
        }
    }

    static void writeLiquids(LiquidModule liquids, Writes out, ContentHeader.IdMapping ids){
        int amount = 0;
        for(float liquid : liquids.liquids) if(liquid > 0) amount++;
        out.s(amount);
        for(int id = 0; id < liquids.liquids.length; id++){
            if(liquids.liquids[id] > 0){
                out.s(ids.requireStream(ContentType.LIQUID, id));
                out.f(liquids.liquids[id]);
            }
        }
    }

    /** {@code PowerModule.read}: laser links, then a status that is 0 when not finite. */
    static void readPower(PowerModule power, Reads in){
        power.links.clear();
        int amount = in.s();
        for(int i = 0; i < amount; i++) power.links.add(in.i());
        float status = in.f();
        power.status = Float.isNaN(status) || Float.isInfinite(status) ? 0f : status;
    }

    static void writePower(PowerModule power, Writes out){
        out.s(power.links.size);
        for(int i = 0; i < power.links.size; i++) out.i(power.links.get(i));
        out.f(power.status);
    }

    /** The building standing at a packed position ({@code Point2.pack}), or null: vanilla's {@code world.build(pos)}. */
    @FunctionalInterface
    public interface PlacedAt{
        Building at(int pos);
    }
}
