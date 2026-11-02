package com.xpdustry.shard.oracle;

import arc.func.*;
import arc.util.io.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.type.*;
import mindustry.world.*;
import com.xpdustry.shard.oracle.Oracle.*;
import com.xpdustry.shard.oracle.Vanilla.*;

import java.io.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Reference encoders: what vanilla itself writes, byte for byte, to compare Shard's encoders against.
 * Every method resets or replaces vanilla's world first unless it says "current world".
 */
public final class Golden{
    /** A world stream as {@code NetServer.sendWorldData} produces it: raw, and deflated by {@code FastDeflaterOutputStream}. */
    public record WorldStreamBytes(byte[] raw, byte[] deflated){}

    /**
     * One entity's bytes: {@code sync} is {@code writeSync} (entity snapshots, after {@code i32 id, u8 classId}),
     * {@code save} is {@code write} (world streams and saves, after {@code u8 classId, i32 id}). {@code error} is set
     * when vanilla could not spawn or write it.
     */
    public record EntityBytes(String type, int id, int classId, String className, byte[] sync, byte[] save, String error){
        public String json(){
            return Json.write(Views.map("type", type, "id", id, "classId", classId, "className", className,
                "sync", sync == null ? null : Hex.of(sync), "save", save == null ? null : Hex.of(save), "error", error));
        }
    }

    /**
     * One building's bytes. {@code base} is {@code writeBase} (health, rotation, team, modules, efficiency),
     * {@code write} is the block-specific {@code write}, and {@code chunk} is what the map section of a world stream
     * holds after the chunk's {@code i32} length: {@code u8 version()} then {@code writeAll} (= base + write).
     */
    public record BuildingBytes(String block, String className, int version, byte[] base, byte[] write, byte[] chunk, String error){}

    //region world streams

    /** Hosts a built-in map (see {@link Vanilla#findMap}) in survival and writes the stream a new player gets. */
    public static WorldStreamBytes worldStream(String map){
        return worldStream(map, Gamemode.survival, p -> {});
    }

    /**
     * Hosts a map as the server's {@code host} command does and runs one server frame (a real server has always run
     * some before anyone connects; the first frame updates team state the stream contains). Then writes the world
     * stream for a new player named "oracle" on the default team, as the server does right after the connect packet
     * (the player is not added yet). {@code setup} can change the player first.
     */
    public static WorldStreamBytes worldStream(String map, Gamemode mode, Cons<Player> setup){
        Vanilla.host(map, mode);
        Vanilla.frame();
        Player player = Player.create();
        player.name = "oracle";
        player.team(state.rules.defaultTeam);
        setup.get(player);
        return worldStream(player);
    }

    /**
     * Hosts a map in survival as the server's {@code host} command does, {@code logic.play()} included, and writes the
     * stream a new player named "oracle" on the default team gets before any frame runs: the world as it is at load.
     */
    public static WorldStreamBytes worldStreamAtLoad(String map){
        Vanilla.host(map, Gamemode.survival);
        Player player = Player.create();
        player.name = "oracle";
        player.team(state.rules.defaultTeam);
        return worldStream(player);
    }

    /** The world stream of the current world for this player. */
    public static WorldStreamBytes worldStream(Player player){
        Vanilla.boot();
        var raw = new ByteArrayOutputStream();
        NetworkIO.writeWorld(player, raw);
        var deflated = new ByteArrayOutputStream();
        NetworkIO.writeWorld(player, new arc.util.io.FastDeflaterOutputStream(deflated));
        return new WorldStreamBytes(raw.toByteArray(), deflated.toByteArray());
    }

    //endregion
    //region entities

    /** Every unit type, spawned as below, in id order. */
    public static List<EntityBytes> units(){
        List<EntityBytes> out = new ArrayList<>();
        for(UnitType type : content.units()) out.add(unit(type));
        return out;
    }

    public static EntityBytes unit(String type){
        return unit(content.unit(type));
    }

    /**
     * Spawns a unit of this type in a fresh 32x32 stone world (team sharded, at (128, 128), rotation 90, unit cap
     * off), adds it (except block units, which vanilla never adds), and writes it. Its id is 0.
     */
    public static EntityBytes unit(UnitType type){
        return unit(type, u -> {});
    }

    public static EntityBytes unit(UnitType type, Cons<Unit> setup){
        Vanilla.emptyWorld(32, 32);
        state.rules.disableUnitCap = true;
        Unit unit = null;
        try{
            unit = type.create(Team.sharded);
            unit.set(128f, 128f);
            unit.rotation(90f);
            //block units (turrets a player controls) exist only inside their building, never in the world
            if(!(unit instanceof BlockUnitc)) unit.add();
            setup.get(unit);
            Vanilla.runPosted();
            Unit u = unit;
            byte[] sync = bytes(u::writeSync);
            u.beforeWrite();
            byte[] save = bytes(u::write);
            return new EntityBytes(type.name, u.id, u.classId(), Views.className(u.getClass()), sync, save, null);
        }catch(Throwable t){
            return new EntityBytes(type.name, unit == null ? -1 : unit.id, unit == null ? -1 : unit.classId(),
                unit == null ? null : Views.className(unit.getClass()), null, null, DecodeException.describe(t));
        }
    }

    /** An entity's {@code writeSync} and {@code write} bytes, as it is now (current world). */
    public static EntityBytes entity(Entityc entity){
        byte[] sync = entity instanceof Syncc s ? bytes(s::writeSync) : null;
        entity.beforeWrite();
        byte[] save = bytes(entity::write);
        String type = entity instanceof Unit u ? u.type.name : entity.getClass().getSimpleName();
        return new EntityBytes(type, entity.id(), entity.classId(), Views.className(entity.getClass()), sync, save, null);
    }

    //endregion
    //region buildings

    /** Every block that has a building, in its default state, in id order. */
    public static List<BuildingBytes> buildings(){
        List<BuildingBytes> out = new ArrayList<>();
        for(Block block : content.blocks()){
            if(block.hasBuilding()) out.add(building(block, 0, b -> {}));
        }
        return out;
    }

    public static BuildingBytes building(String block){
        return building(content.block(block), 0, b -> {});
    }

    /**
     * Places a block with {@code tile.setBlock} (team sharded) at the center of a fresh 32x32 stone world, lets
     * {@code setup} change it (e.g. {@code b -> b.handleItem(null, Items.copper)}), and writes it.
     */
    public static BuildingBytes building(Block block, int rotation, Cons<Building> setup){
        Vanilla.emptyWorld(32, 32);
        try{
            Tile tile = world.tile(16, 16);
            tile.setBlock(block, Team.sharded, rotation);
            Building build = tile.build;
            setup.get(build);
            Vanilla.runPosted();
            return building(build);
        }catch(Throwable t){
            return new BuildingBytes(block.name, null, -1, null, null, null, DecodeException.describe(t));
        }
    }

    /** A building's bytes as it is now (current world). */
    public static BuildingBytes building(Building build){
        byte[] base = bytes(build::writeBase);
        byte[] write = bytes(build::write);
        byte[] chunk = bytes(w -> {
            w.b(build.version());
            build.writeAll(w);
        });
        return new BuildingBytes(build.block.name, Views.className(build.getClass()), build.version(), base, write, chunk, null);
    }

    //endregion
    //region packets

    /**
     * Runs code that calls {@code Call.x(...)} with vanilla's net in server mode and returns the frames it sent, in
     * order. Use {@link Vanilla#connection()} as the target of {@code Call.x(con, ...)}.
     * <pre>{@code Wire.Frame f = Golden.call(() -> Call.sendMessage("hi"));}</pre>
     */
    public static List<Wire.Frame> calls(Runnable action){
        return calls(NetMode.server, action);
    }

    /** As {@link #calls(Runnable)}, as the given side ({@code client} for client-to-server calls). */
    public static List<Wire.Frame> calls(NetMode sender, Runnable action){
        Vanilla.boot();
        int before = Vanilla.captured().size();
        Vanilla.net(sender, action);
        List<Wire.Frame> out = new ArrayList<>();
        for(var sent : Vanilla.captured().subList(before, Vanilla.captured().size())){
            if(sent.frame() != null) out.add(sent.frame());
        }
        return out;
    }

    /** As {@link #calls(Runnable)}, for code that sends exactly one packet. */
    public static Wire.Frame call(Runnable action){
        List<Wire.Frame> frames = calls(action);
        if(frames.size() != 1) throw new IllegalStateException("expected one packet, vanilla sent " + frames);
        return frames.getFirst();
    }

    /** A packet object, serialized by the side that sends packets going this way. */
    public static Wire.Frame packet(Packet packet, Direction direction){
        return Vanilla.net(direction.writer(), () -> Wire.frame(packet));
    }

    //endregion

    /** What a vanilla writer produces, through {@code Writes} over a {@code DataOutputStream}, as vanilla does. */
    public static byte[] bytes(Cons<Writes> writer){
        var out = new ByteArrayOutputStream();
        writer.get(new Writes(new DataOutputStream(out)));
        return out.toByteArray();
    }

    private Golden(){}
}
