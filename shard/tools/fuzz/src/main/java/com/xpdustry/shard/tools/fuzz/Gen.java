package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.AdminAction;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.nio.charset.*;
import java.util.*;

/**
 * Structure-aware client packets: a valid body of each packet a client may send, built with {@code :wire}'s generated
 * records and filled with values chosen to hurt. Each value is, by turns, a live target in the sender's world, one from
 * another world, a boundary (0, -1, extremes, NaN, infinities) or noise; lists are empty, short or as long as the frame
 * allows. {@link #body} returns the payload; {@link Mutate} damages it further.
 *
 * <p>The ids it covers are {@link #IDS}, the 38 client Calls of note 25 §7 (the connect-flow ones included: confirm,
 * chat, ping, requestWorld, requestAssets), plus {@code ConnectPacket} ({@link #connect}) and any server-only id, which a
 * client must not send.
 */
final class Gen{
    /** Every packet id a client may send, ascending (Packets.TYPES with {@code toServerAllowed}). */
    static final int[] IDS = Packets.TYPES.stream().filter(t -> !t.framework() && t.direction().toServerAllowed()).mapToInt(PacketType::id).toArray();
    /** Ids only a server sends: a client sending one is out of protocol. */
    static final int[] SERVER_ONLY = Packets.TYPES.stream().filter(t -> !t.framework() && !t.direction().toServerAllowed()).mapToInt(PacketType::id).toArray();

    private final Random rnd;
    /** The sender's world and every world, for cross-world ids. */
    private Targets own = Targets.NONE;
    private List<Targets> all = List.of();

    Gen(Random rnd){
        this.rnd = rnd;
    }

    void aim(Targets own, List<Targets> all){
        this.own = own;
        this.all = all;
    }

    /** The world whose content the body is written against. */
    WireContext wire(){
        return own.wire() != null ? own.wire() : Lenient.INSTANCE;
    }

    /** A generated packet of {@code id}, or null for the ids it builds by hand ({@link #body}). */
    Packet packet(int id){
        return switch(id){
            case Packets.ClientSnapshot.ID -> snapshot();
            case Packets.TileConfig.ID -> new Packets.TileConfig(-1, build(), object(2));
            case Packets.RotateBlock.ID -> new Packets.RotateBlock(-1, build(), rnd.nextBoolean());
            case Packets.TileTap.ID -> new Packets.TileTap(-1, pos());
            case Packets.RequestBlockSnapshot.ID -> new Packets.RequestBlockSnapshot(pos());
            case Packets.DeletePlans.ID -> new Packets.DeletePlans(-1, ints(this::pos));
            case Packets.ClientPlanSnapshot.ID -> new Packets.ClientPlanSnapshot(anInt(), clientPlans());
            case Packets.UnitControl.ID -> new Packets.UnitControl(-1, unitRef());
            case Packets.UnitClear.ID -> new Packets.UnitClear(-1);
            case Packets.CommandUnits.ID -> new Packets.CommandUnits(-1, ints(this::unit), build(), unitRef(), vec(), rnd.nextBoolean(), rnd.nextBoolean());
            case Packets.SetUnitCommand.ID -> new Packets.SetUnitCommand(-1, ints(this::unit), small(16));
            case Packets.SetUnitStance.ID -> new Packets.SetUnitStance(-1, ints(this::unit), small(16), rnd.nextBoolean());
            case Packets.CommandBuilding.ID -> new Packets.CommandBuilding(-1, ints(this::build), vec());
            case Packets.BuildingControlSelect.ID -> new Packets.BuildingControlSelect(-1, build());
            case Packets.RequestItem.ID -> new Packets.RequestItem(-1, build(), (short)content(own.items()), amount());
            case Packets.TransferInventory.ID -> new Packets.TransferInventory(-1, build());
            case Packets.DropItem.ID -> new Packets.DropItem(aFloat());
            case Packets.PingLocation.ID -> new Packets.PingLocation(-1, worldFloat(), worldFloat(), string(rnd.nextInt(4) == 0 ? 4000 : 60));
            case Packets.SetPlayerTeamEditor.ID -> new Packets.SetPlayerTeamEditor(-1, team());
            case Packets.AdminRequest.ID -> new Packets.AdminRequest(player(), AdminAction.values()[rnd.nextInt(AdminAction.values().length)], object(1));
            case Packets.RequestUnitPayload.ID -> new Packets.RequestUnitPayload(-1, unitRef());
            case Packets.RequestBuildPayload.ID -> new Packets.RequestBuildPayload(-1, build());
            case Packets.RequestDropPayload.ID -> new Packets.RequestDropPayload(-1, worldFloat(), worldFloat());
            case Packets.MenuChoose.ID -> new Packets.MenuChoose(-1, anInt(), anInt());
            case Packets.TextInputResult.ID -> new Packets.TextInputResult(-1, anInt(), string(rnd.nextInt(4) == 0 ? 4000 : 40));
            case Packets.MenuBuilderChoose.ID -> new Packets.MenuBuilderChoose(-1, anInt(), menuResult());
            case Packets.ServerPacketReliable.ID -> new Packets.ServerPacketReliable(string(30), string(2000));
            case Packets.ServerPacketUnreliable.ID -> new Packets.ServerPacketUnreliable(string(30), string(2000));
            case Packets.ServerBinaryPacketReliable.ID -> new Packets.ServerBinaryPacketReliable(string(30), bytes(4000));
            case Packets.ServerBinaryPacketUnreliable.ID -> new Packets.ServerBinaryPacketUnreliable(string(30), bytes(4000));
            case Packets.ClientLogicDataReliable.ID -> new Packets.ClientLogicDataReliable(string(30), object(2));
            case Packets.ClientLogicDataUnreliable.ID -> new Packets.ClientLogicDataUnreliable(string(30), object(2));
            case Packets.RequestDebugStatus.ID -> new Packets.RequestDebugStatus();
            case Packets.ConnectConfirm.ID -> new Packets.ConnectConfirm();
            case Packets.RequestWorld.ID -> new Packets.RequestWorld();
            case Packets.SendChatMessage.ID -> new Packets.SendChatMessage(chat());
            case Packets.Ping.ID -> new Packets.Ping(rnd.nextLong());
            case Packets.RequestAssets.ID -> new Packets.RequestAssets(shorts());
            default -> null;
        };
    }

    /**
     * The payload of a packet of {@code id}: the generated record's client form, a connect packet, or noise for an id no
     * record covers (or one whose writer refuses the values, as a string over its limit).
     */
    byte[] body(int id){
        if(id == Link.CONNECT) return connect();
        Packet packet = packet(id);
        if(packet != null){
            var out = new Writes(64);
            try{
                packet.write(out, false, wire());
                return out.toByteArray();
            }catch(RuntimeException refused){
                // the codec keeps vanilla's write-side limits; the bytes past them are noise's job
            }
        }
        return bytes(rnd.nextInt(8) == 0 ? 2000 : 64);
    }

    /** A packet id: mostly ones a client sends, sometimes a server-only one or one past the registry. */
    int id(){
        int roll = rnd.nextInt(100);
        if(roll < 92) return IDS[rnd.nextInt(IDS.length)];
        if(roll < 98) return SERVER_ONLY[rnd.nextInt(SERVER_ONLY.length)];
        return Packets.TYPES.size() + rnd.nextInt(256 - Packets.TYPES.size());
    }

    // ---- packets with structure of their own

    Packets.ClientSnapshot snapshot(){
        int unit = rnd.nextInt(3) == 0 ? -1 : unit();
        float x = worldFloat(), y = worldFloat();
        return new Packets.ClientSnapshot(anInt(), unit, rnd.nextInt(5) == 0, x, y, worldFloat(), worldFloat(), aFloat(), aFloat(), aFloat(), aFloat(),
            rnd.nextBoolean() ? -1 : pos(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), (short)content(own.blocks()),
            small(8), plans(), worldFloat(), worldFloat(), aFloat(), aFloat());
    }

    /** {@code PlansQueueNet}: null, empty, up to 20 (the reader's limit) or a few over. */
    List<Codecs.Plan> plans(){
        int roll = rnd.nextInt(10);
        if(roll < 3) return null;
        int n = roll < 5 ? 0 : roll < 9 ? 1 + rnd.nextInt(20) : 21 + rnd.nextInt(10);
        var out = new ArrayList<Codecs.Plan>(n);
        for(int i = 0; i < n; i++){
            if(rnd.nextInt(3) == 0) out.add(Codecs.Plan.breaking(pos()));
            else out.add(new Codecs.Plan(false, pos(), content(own.blocks()), small(4), rnd.nextInt(3) == 0 ? object(1) : null));
        }
        return out;
    }

    List<Codecs.ClientPlan> clientPlans(){
        int n = rnd.nextInt(5) == 0 ? rnd.nextInt(300) : rnd.nextInt(8);
        var out = new ArrayList<Codecs.ClientPlan>(n);
        for(int i = 0; i < n; i++){
            out.add(new Codecs.ClientPlan(coord(own.width()), coord(own.height()), content(own.blocks()), small(4),
                rnd.nextInt(3) == 0 ? planConfig() : null));
        }
        return out;
    }

    /** A plan preview's config: the tags {@code readClientPlans} allows, by turns, and others. */
    private Object planConfig(){
        return switch(rnd.nextInt(7)){
            case 0 -> anInt();
            case 1 -> (long)anInt();
            case 2 -> aFloat();
            case 3 -> string(40);
            case 4 -> new WireObjects.ContentRef(rnd.nextInt(20), anInt());
            case 5 -> new Point2(anInt() & 0xff, anInt() & 0xff);
            default -> object(1);
        };
    }

    Codecs.MenuResult menuResult(){
        var values = new LinkedHashMap<String, Object>();
        int n = rnd.nextInt(5) == 0 ? rnd.nextInt(600) : rnd.nextInt(6);
        for(int i = 0; i < n; i++){
            Object value = switch(rnd.nextInt(3)){
                case 0 -> aFloat();
                case 1 -> rnd.nextBoolean();
                default -> nonNull(string(40));
            };
            values.put(nonNull(string(20)) + i, value);
        }
        return new Codecs.MenuResult(rnd.nextLong(), string(60), values);
    }

    /** {@code ConnectPacket} with its fields mutated: version, names, uuid and its checksum, mods. */
    byte[] connect(){
        var out = new Writes(128);
        out.i(switch(rnd.nextInt(6)){
            case 0 -> anInt();
            case 1 -> -1;
            default -> 160;
        });
        Link.string(out, rnd.nextInt(4) == 0 ? string(30) : "official");
        Link.string(out, rnd.nextInt(3) == 0 ? string(200) : "fz" + rnd.nextInt(1000));
        Link.string(out, rnd.nextInt(4) == 0 ? string(30) : "en");
        Link.string(out, rnd.nextInt(4) == 0 ? string(30) : Base64.getEncoder().encodeToString(bytes8()));
        byte[] uuid = bytes8();
        out.b(uuid);
        var crc = new java.util.zip.CRC32();
        crc.update(uuid);
        out.l(rnd.nextInt(4) == 0 ? rnd.nextLong() : crc.getValue());
        out.bool(rnd.nextBoolean());
        out.i(rnd.nextInt());
        int mods = rnd.nextInt(6) == 0 ? rnd.nextInt(256) - 128 : 0;
        out.b(mods);
        for(int i = 0; i < Math.max(0, Math.min(mods, 20)); i++) Link.string(out, string(40));
        return out.toByteArray();
    }

    // ---- values

    private int pick(int[] own, java.util.function.Function<Targets, int[]> other){
        int roll = rnd.nextInt(100);
        if(roll < 55 && own.length > 0) return own[rnd.nextInt(own.length)];
        if(roll < 75 && !all.isEmpty()){
            int[] there = other.apply(all.get(rnd.nextInt(all.size())));
            if(there.length > 0) return there[rnd.nextInt(there.length)];
        }
        return anInt();
    }

    int unit(){
        return pick(own.units(), Targets::units);
    }

    int player(){
        return pick(own.players(), Targets::players);
    }

    /** A building's position: a live one here, another world's, a tile or noise. */
    int build(){
        if(rnd.nextInt(5) == 0) return pos();
        return pick(own.buildPositions(), Targets::buildPositions);
    }

    /** A tile position: on the map, just off it, or any int. */
    int pos(){
        int roll = rnd.nextInt(10);
        if(roll < 6) return Point2.pack(rnd.nextInt(Math.max(1, own.width())), rnd.nextInt(Math.max(1, own.height())));
        if(roll < 8) return Point2.pack(coord(own.width()), coord(own.height()));
        if(roll < 9 && own.buildPositions().length > 0) return own.buildPositions()[rnd.nextInt(own.buildPositions().length)];
        return anInt();
    }

    /** A tile coordinate in a {@code u16}: inside, on the edge, or past it. */
    int coord(int size){
        return switch(rnd.nextInt(6)){
            case 0 -> size;
            case 1 -> size - 1;
            case 2 -> 0xffff;
            case 3 -> rnd.nextInt(0x10000);
            default -> rnd.nextInt(Math.max(1, size));
        };
    }

    Codecs.UnitRef unitRef(){
        return switch(rnd.nextInt(8)){
            case 0 -> Codecs.UnitRef.NONE;
            case 1 -> new Codecs.UnitRef(1, build());
            case 2 -> new Codecs.UnitRef(rnd.nextInt(256), anInt());
            default -> new Codecs.UnitRef(2, unit());
        };
    }

    int team(){
        return rnd.nextInt(3) == 0 ? rnd.nextInt(256) : own.teams()[rnd.nextInt(own.teams().length)];
    }

    /** A content id: a valid one, the first past the range, or any {@code short}. */
    int content(int count){
        return switch(rnd.nextInt(8)){
            case 0 -> count;
            case 1 -> -1;
            case 2 -> (short)rnd.nextInt();
            default -> rnd.nextInt(Math.max(1, count));
        };
    }

    int amount(){
        return switch(rnd.nextInt(5)){
            case 0 -> Integer.MAX_VALUE;
            case 1 -> Integer.MIN_VALUE;
            case 2 -> -1;
            default -> rnd.nextInt(1000);
        };
    }

    int small(int range){
        return rnd.nextInt(6) == 0 ? (rnd.nextBoolean() ? 255 : rnd.nextInt(256) - 128) : rnd.nextInt(range);
    }

    int anInt(){
        return switch(rnd.nextInt(10)){
            case 0 -> 0;
            case 1 -> -1;
            case 2 -> Integer.MAX_VALUE;
            case 3 -> Integer.MIN_VALUE;
            case 4 -> rnd.nextInt();
            case 5 -> 0xffff;
            case 6 -> Short.MAX_VALUE + 1;
            default -> rnd.nextInt(1000);
        };
    }

    float aFloat(){
        return switch(rnd.nextInt(12)){
            case 0 -> Float.NaN;
            case 1 -> Float.POSITIVE_INFINITY;
            case 2 -> Float.NEGATIVE_INFINITY;
            case 3 -> Float.MAX_VALUE;
            case 4 -> -Float.MAX_VALUE;
            case 5 -> Float.MIN_VALUE;
            case 6 -> -0f;
            case 7 -> Float.intBitsToFloat(rnd.nextInt());
            case 8 -> 1e9f * (rnd.nextFloat() - 0.5f);
            default -> 400f * (rnd.nextFloat() - 0.5f);
        };
    }

    /** A world coordinate: mostly on the map, else {@link #aFloat}. */
    float worldFloat(){
        if(rnd.nextInt(4) == 0) return aFloat();
        return rnd.nextFloat() * Math.max(own.width(), own.height()) * 8f;
    }

    Codecs.Vec2 vec(){
        return new Codecs.Vec2(worldFloat(), worldFloat());
    }

    /** An {@code int[]}: empty, short, or up to the frame's room, of values from {@code values}. */
    int[] ints(java.util.function.IntSupplier values){
        int n = switch(rnd.nextInt(10)){
            case 0 -> 0;
            case 1 -> 1000 + rnd.nextInt(3000);
            case 2 -> 100 + rnd.nextInt(400);
            default -> 1 + rnd.nextInt(12);
        };
        int[] out = new int[n];
        // a long list repeats a few values, as an attacker's would, and stays inside the frame once compressed
        int distinct = Math.min(n, 64);
        for(int i = 0; i < n; i++) out[i] = i < distinct ? values.getAsInt() : out[rnd.nextInt(distinct)];
        return out;
    }

    short[] shorts(){
        int n = rnd.nextInt(5) == 0 ? rnd.nextInt(4000) : rnd.nextInt(8);
        short[] out = new short[n];
        for(int i = 0; i < n; i++) out[i] = (short)(rnd.nextBoolean() ? rnd.nextInt(300) : rnd.nextInt());
        return out;
    }

    byte[] bytes(int max){
        byte[] out = new byte[rnd.nextInt(max + 1)];
        rnd.nextBytes(out);
        return out;
    }

    private byte[] bytes8(){
        byte[] out = new byte[8];
        rnd.nextBytes(out);
        return out;
    }

    /** A string: null, empty, short, long, multibyte, a chat command or markup. */
    String string(int max){
        return switch(rnd.nextInt(12)){
            case 0 -> null;
            case 1 -> "";
            case 2 -> "x".repeat(rnd.nextInt(max + 1));
            case 3 -> "é中😀".repeat(Math.max(1, rnd.nextInt(max + 1) / 4));
            case 4 -> "[scarlet]" + "[".repeat(rnd.nextInt(20)) + "]";
            case 5 -> new String(bytes(Math.min(max, 64)), StandardCharsets.ISO_8859_1);
            case 6 -> "\ud800" + "a".repeat(rnd.nextInt(10)); // a lone surrogate
            default -> "s" + rnd.nextInt(100000);
        };
    }

    private static String nonNull(String value){
        return value == null ? "" : value;
    }

    /** Chat: commands that move, sync, vote and list, markup, and lines over vanilla's 150-character limit. */
    String chat(){
        return switch(rnd.nextInt(10)){
            case 0 -> "/world " + (rnd.nextBoolean() ? rnd.nextInt(8) : all.isEmpty() ? "x" : all.get(rnd.nextInt(all.size())).world());
            case 1 -> "/sync";
            case 2 -> "/votekick " + string(20);
            case 3 -> "/" + string(30);
            case 4 -> "a".repeat(140 + rnd.nextInt(40));
            case 5 -> "/help " + anInt();
            default -> nonNull(string(80));
        };
    }

    /**
     * A {@code readObjectSafe} value: every tag it admits, arrays of them nested {@code depth} deep, entity references
     * into this world and others, and lengths at and past the safe limits.
     */
    Object object(int depth){
        int tag = rnd.nextInt(depth > 0 ? 24 : 23);
        return switch(tag){
            case 0 -> null;
            case 1 -> anInt();
            case 2 -> (long)anInt() * anInt();
            case 3 -> aFloat();
            case 4 -> string(rnd.nextInt(6) == 0 ? 1300 : 40);
            case 5 -> new WireObjects.ContentRef(rnd.nextInt(WireObjects.CONTENT_TYPES + 2) - 1, content(own.blocks()));
            case 6 -> new WireObjects.IntSeqValue(ints(this::anInt));
            case 7 -> new Point2(anInt() % 4000, anInt() % 4000);
            case 8 -> {
                var points = new Point2[rnd.nextInt(5) == 0 ? rnd.nextInt(256) : rnd.nextInt(6)];
                for(int i = 0; i < points.length; i++) points[i] = new Point2(rnd.nextInt(600) - 100, rnd.nextInt(600) - 100);
                yield points;
            }
            case 9 -> new WireObjects.TechNodeRef(rnd.nextInt(WireObjects.CONTENT_TYPES), content(own.blocks()));
            case 10 -> rnd.nextBoolean();
            case 11 -> rnd.nextDouble() * anInt();
            case 12 -> new WireObjects.BuildingRef(build());
            case 13 -> new WireObjects.LAccessRef(rnd.nextInt(WireObjects.LACCESS_VALUES + 3) - 1);
            case 14 -> bytes(rnd.nextInt(6) == 0 ? 30_000 : 64);
            case 15 -> {
                var flags = new boolean[rnd.nextInt(5) == 0 ? rnd.nextInt(2000) : rnd.nextInt(10)];
                for(int i = 0; i < flags.length; i++) flags[i] = rnd.nextBoolean();
                yield flags;
            }
            case 16 -> new WireObjects.UnitIdRef(unit());
            case 17 -> {
                var vecs = new Codecs.Vec2[rnd.nextInt(5) == 0 ? rnd.nextInt(1100) : rnd.nextInt(6)];
                for(int i = 0; i < vecs.length; i++) vecs[i] = vec();
                yield vecs;
            }
            case 18 -> vec();
            case 19 -> new WireObjects.TeamRef(team());
            case 20 -> ints(this::anInt);
            case 21 -> new WireObjects.UnitCommandRef(small(16));
            case 22 -> rnd.nextBoolean() ? (Object)(byte)anInt() : (short)anInt();
            default -> {
                var array = new Object[rnd.nextInt(5) == 0 ? rnd.nextInt(1100) : rnd.nextInt(6)];
                for(int i = 0; i < array.length; i++) array[i] = object(depth - 1);
                yield array;
            }
        };
    }

    /** Content facts for writing without a world: every block exists and rotates. */
    static final class Lenient implements WireContext{
        static final Lenient INSTANCE = new Lenient();

        @Override
        public boolean statusDynamic(int status){
            return false;
        }

        @Override
        public boolean blockRotates(int block){
            return true;
        }

        @Override
        public boolean blockExists(int block){
            return true;
        }

        @Override
        public boolean unlockable(int type, int id){
            return true;
        }

        @Override
        public boolean tileExists(int pos){
            return true;
        }

        @Override
        public int syncedClass(int id){
            return -1;
        }
    }
}
