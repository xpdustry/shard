package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;

/**
 * Vanilla's {@code TypeIO} codecs over Shard's value types, byte for byte (core/src/mindustry/io/TypeIO.java at
 * v160.7). Readers consume exactly the bytes vanilla's do, and throw {@link WireException} where vanilla's throw:
 * every limit, enum range and content check below is vanilla's. Writers fail before writing anything when a value
 * can't be encoded. The tagged {@code Object} union is in {@link WireObjects}.
 *
 * <h2>String limits</h2>
 * Strings are Java's modified UTF-8 behind a u16 byte length ({@code writeUTF}): U+0000 is {@code C0 80}, an astral
 * character is two 3-byte surrogates, and a reader also accepts a raw {@code 00} byte. Limits count encoded bytes
 * unless noted.
 * <table>
 * <tr><th>Codec</th><th>Direction</th><th>Limit</th></tr>
 * <tr><td>any write ({@code writeUTF})</td><td>both</td><td>65,535 bytes</td></tr>
 * <tr><td>{@code readString}, UiBuilder, ConnectPacket</td><td>both</td><td>65,535 bytes (the u16)</td></tr>
 * <tr><td>{@code readObject} tag 4</td><td>server to client</td><td>65,535 bytes</td></tr>
 * <tr><td>{@code readObjectSafe} tag 4 (configs, plans, logic data)</td><td>client to server</td><td>1,200 bytes,
 *     checked on the length before decoding (TypeIO.java:231)</td></tr>
 * <tr><td>MenuResult result</td><td>client to server</td><td>500 bytes</td></tr>
 * <tr><td>MenuResult keys and values</td><td>client to server</td><td>1,000 bytes each, and at most the characters
 *     left of 6,000, counted in UTF-16 units (TypeIO.java:352)</td></tr>
 * <tr><td>Rules, Objectives, ObjectiveMarker JSON</td><td>server to client</td><td>100,000, 59,999 and 40,000 bytes
 *     of UTF-8</td></tr>
 * </table>
 */
public final class Codecs{
    /** {@code TypeIO.maxSyncedPlans}: the most plans a {@code PlansQueueNet} carries. */
    public static final int MAX_SYNCED_PLANS = 20;
    /** {@code Vars.maxPlayerPreviewPlans}: the most plans a {@code ClientPlans} carries. */
    public static final int MAX_PREVIEW_PLANS = 1000;
    /** {@code getMaxPlans}: a plan queue stops after the plan whose config bytes take the total past this. */
    public static final int MAX_PLAN_CONFIG_BYTES = 500;
    /** {@code MenuResult.maxResultLen}, {@code maxValueLen}, {@code maxTotalStringLen}, {@code maxTotalValues}. */
    public static final int MENU_MAX_RESULT = 500, MENU_MAX_VALUE = 1000, MENU_MAX_TOTAL_CHARS = 6000, MENU_MAX_VALUES = 500;
    /** UiBuilder node types are the {@code UiKey}s before {@code row}; property keys are all 64 {@code UiKey}s. */
    public static final int UI_NODE_TYPES = 13, UI_KEYS = 64;
    /**
     * The deepest UiBuilder tree Shard reads or writes, counting the root as 1. Vanilla's reader recurses until its
     * stack overflows; Shard refuses deeper trees instead, on both sides.
     */
    public static final int UI_MAX_DEPTH = 64;
    /** {@code writeTraceInfo} sends at most this many past IPs and names. */
    public static final int TRACE_MAX_STRINGS = 12;
    /** {@code writeTile} for null: {@code Point2.pack(-1, -1)}. */
    public static final int NO_TILE = -1;
    /** Every other reference codec writes null as -1: entities, buildings, blocks, items, liquids, weathers. */
    public static final int NONE = -1;
    /** {@code writeCommand} and {@code writeStance} for null. */
    public static final int NO_COMMAND = 255;
    /** {@code ContentType} ordinals of the content these codecs check. */
    public static final int CONTENT_STATUS = 5, CONTENT_UNIT = 6;

    private Codecs(){}

    //region value types

    public record Vec2(float x, float y){
        public static final Vec2 ZERO = new Vec2(0, 0);
    }

    /** @param item a content id, or -1 for none */
    public record ItemStack(int item, int amount){}

    public record LiquidStack(int liquid, float amount){}

    /** @param state bit 0 shoot, bit 1 rotate */
    public record Mount(int state, float aimX, float aimY){}

    /**
     * {@code writeUnit}: a u8 kind and an i32. Kind 0 is none (vanilla writes 0), 1 a block's unit (the building's
     * position), 2 a unit (its id). A reader keeps any kind; vanilla resolves the others to null.
     */
    public record UnitRef(int kind, int ref){
        public static final UnitRef NONE = new UnitRef(0, 0);

        public static UnitRef unit(int id){
            return new UnitRef(2, id);
        }

        public static UnitRef block(int pos){
            return new UnitRef(1, pos);
        }
    }

    /**
     * A status effect on a unit. A dynamic effect also carries the multipliers that are not 1 and an armor override
     * that is not negative; other effects carry only the effect and its time.
     */
    public record StatusEntry(int effect, float time, float damageMultiplier, float healthMultiplier, float speedMultiplier,
                              float reloadMultiplier, float buildSpeedMultiplier, float dragMultiplier, float armorOverride){
        public static StatusEntry of(int effect, float time){
            return new StatusEntry(effect, time, 1f, 1f, 1f, 1f, 1f, 1f, -1f);
        }
    }

    /**
     * A build plan ({@code writePlan}).
     * @param pos      a packed tile position
     * @param block    for a placement: the block's id, which vanilla does not check here
     * @param rotation for a placement: a signed byte
     * @param config   for a placement: a {@link WireObjects} value; null if the plan said it has none
     */
    public record Plan(boolean breaking, int pos, int block, int rotation, Object config){
        public static Plan breaking(int pos){
            return new Plan(true, pos, NONE, 0, null);
        }
    }

    /**
     * A placement preview ({@code writeClientPlans}).
     * @param rotation only on the wire for blocks that rotate
     * @param config   null, or a number, boolean or {@link WireObjects.ContentRef}
     */
    public record ClientPlan(int x, int y, int block, int rotation, Object config){}

    /** A unit controller as {@code writeController} sends it: only its kind and a little state. */
    public sealed interface Controller{}

    /** Type 0: a player, by entity id. */
    public record PlayerControl(int player) implements Controller{}

    /** Type 1: the formation controller of old saves. Read only. */
    public record FormationControl(int ref) implements Controller{}

    /** Type 3: a logic processor, by building position. */
    public record LogicControl(int processor) implements Controller{}

    /** Type 5: an assembler drone. */
    public record AssemblerControl() implements Controller{}

    /** Type 2, or a type vanilla doesn't know: any other AI. Clients install a generic one. */
    public record OtherControl(int type) implements Controller{
        public static final OtherControl GENERIC = new OtherControl(2);
    }

    /**
     * Types 4, 6, 7, 8 and 9: a {@code CommandAI}. v160.7 writes type 9; the others are older layouts that differ only
     * in which trailing parts they have: 4 none, 6 the command, 7 also the queue, 8 also one stance, 9 a stance list.
     * @param target  null when there is no target position
     * @param attack  null when there is no attack target
     * @param command a command id, or negative for none (a signed byte)
     * @param stances stance ids (u8, 255 for none); type 8 carries exactly one
     */
    public record CommandControl(int type, Vec2 target, Attack attack, int command, List<QueueEntry> queue, List<Integer> stances) implements Controller{
        public static final int TYPE = 9;
    }

    /** @param kind 1 a building (by position); anything else a unit (by id) */
    public record Attack(int kind, int ref){}

    /** A command queue entry: kind 0 a building position, 1 a unit id, 2 a position; any other kind has no body. */
    public record QueueEntry(int kind, int ref, float x, float y){
        public static QueueEntry building(int pos){
            return new QueueEntry(0, pos, 0, 0);
        }

        public static QueueEntry unit(int id){
            return new QueueEntry(1, id, 0, 0);
        }

        public static QueueEntry position(float x, float y){
            return new QueueEntry(2, 0, x, y);
        }
    }

    /** {@code TraceInfo}, an admin's view of a player. */
    public record TraceInfo(String ip, String uuid, String locale, boolean modded, boolean mobile, int timesJoined, int timesKicked,
                            List<String> ips, List<String> names){}

    /**
     * A menu builder's answer.
     * @param result  null when absent
     * @param values  keys to a {@link Float}, {@link Boolean} or {@link String}, in wire order
     */
    public record MenuResult(long token, String result, SequencedMap<String, Object> values){}

    /**
     * A UiBuilder node: a node type ({@code UiKey} ordinal below {@link #UI_NODE_TYPES}) and its properties.
     * A property value is a {@link String}, {@link Float}, {@link Boolean} or nested {@link UiNode}.
     */
    public record UiNode(int type, List<UiEntry> entries){}

    public record UiEntry(int key, Object value){}

    /** A payload in a payload block or a unit's payload list: a unit in its save layout or a building. */
    public sealed interface Payload{}

    /** {@code UnitPayload}: the unit's class id and its {@code write()}, read by the class's save layout. */
    public record UnitPayload(int classId, WireEntity unit) implements Payload{}

    /**
     * {@code BuildPayload}: the block, its version and its {@code writeAll} bytes, which {@link WireContext} reads.
     * @param body the building's base and block body, with no length prefix
     */
    public record BuildPayload(int block, int version, byte[] body) implements Payload{}

    /**
     * {@code UnitSyncContainer}: one unit's id, class id and {@code writeSync}, sent on its own ({@code unitSpawn}).
     * Read, {@code classId} is the class whose layout {@code unit} was read with.
     */
    public record SyncedUnit(int id, int classId, WireEntity unit){}

    //endregion
    //region strings

    /** {@code writeString}: u8 0 for null, else u8 1 and {@code writeUTF}. */
    public static void writeString(Writes write, String value){
        if(value == null){
            write.b(0);
        }else{
            checkUtf(value);
            write.b(1);
            write.str(value);
        }
    }

    /** {@code readString}: any non-zero first byte means present. */
    public static String readString(Reads read){
        return read.b() != 0 ? str(read, 0) : null;
    }

    /** {@code writeUTF}, refusing what it would refuse before writing anything. */
    public static void writeUtf(Writes write, String value){
        checkUtf(value);
        write.str(value);
    }

    /**
     * {@code Reads.str(maxLen)}: a modified UTF-8 string whose encoded length is at most {@code max} bytes when
     * {@code max} is positive. The length is checked before the bytes are decoded.
     */
    public static String str(Reads read, int max){
        try{
            return read.str(max);
        }catch(UncheckedIOException e){
            throw new WireException("string: " + e.getCause().getMessage(), e);
        }catch(StringTooLongException e){
            throw new WireException("string of " + e.length() + " encoded bytes, over " + max, e);
        }
    }

    /** The {@code writeUTF} length of {@code value}: 1 byte for U+0001..U+007F, 2 up to U+07FF and for U+0000, else 3. */
    public static int utfLength(String value){
        int length = 0;
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            length += c >= 0x0001 && c <= 0x007F ? 1 : c <= 0x07FF ? 2 : 3;
        }
        return length;
    }

    static void checkUtf(String value){
        int length = utfLength(value);
        if(length > 65535) throw new WireException("string of " + length + " encoded bytes, over writeUTF's 65,535");
    }

    //endregion
    //region references

    public static void writeTeam(Writes write, int team){
        write.b(team);
    }

    /** {@code Team.get(read.b())}: every byte is a team. */
    public static int readTeam(Reads read){
        return read.ub();
    }

    /**
     * {@code (Player)readEntity}: a synced entity's id. Quirk: generated packets cast what they read to the parameter's
     * type, so an id naming an entity of another class fails, while an unknown id reads as null.
     * @param cast whether an entity of this class id is a {@code type}
     */
    public static int readEntity(Reads read, IntPredicate cast, String type, WireContext context){
        int id = read.i();
        int classId = context.syncedClass(id);
        if(classId >= 0 && !cast.test(classId)) throw new WireException("entity " + id + " is of class " + classId + ", not a " + type);
        return id;
    }

    /** {@code writeUnit}. Null is {@link UnitRef#NONE}, kind 0 and id 0, as vanilla writes it. */
    public static void writeUnit(Writes write, UnitRef unit){
        if(unit == null) unit = UnitRef.NONE;
        write.b(unit.kind);
        write.i(unit.ref);
    }

    public static UnitRef readUnit(Reads read){
        int kind = read.b();
        return new UnitRef(kind, read.i());
    }

    /** {@code writeCommand}, {@code writeStance}: an id, or 255 for none. */
    public static void writeCommand(Writes write, int command){
        write.b(command);
    }

    public static int readCommand(Reads read){
        return read.ub();
    }

    /**
     * A unit's {@code type} field, read by its {@code read} and {@code readSync}: an unknown unit type fails, as the
     * {@code setType(null)} after it does (UnitComp.java:587).
     */
    public static short readUnitType(Reads read, WireContext context){
        short type = read.s();
        if(!context.contentExists(CONTENT_UNIT, type)) throw new WireException("unknown unit type " + type);
        return type;
    }

    /** {@code readContent}: a content type ordinal, which must be one of vanilla's 18, and an id. */
    public static WireObjects.ContentRef readContent(Reads read){
        int type = read.b();
        WireObjects.contentType(type);
        return new WireObjects.ContentRef(type, read.s());
    }

    public static void writeContent(Writes write, WireObjects.ContentRef content){
        write.b(content.type());
        write.s(content.id());
    }

    //endregion
    //region arrays

    /** {@code writeBytes}: an i16 length, so at most 32,767 bytes. */
    public static void writeBytes(Writes write, byte[] bytes){
        if(bytes.length > Short.MAX_VALUE) throw new WireException(bytes.length + " bytes, over writeBytes' 32,767");
        write.s(bytes.length);
        write.b(bytes);
    }

    /** {@code readBytes}: a negative length fails, as its array allocation does. */
    public static byte[] readBytes(Reads read){
        return read.b(count(read, read.s(), 1, "byte array"));
    }

    public static void writeInts(Writes write, int[] ints){
        if(ints.length > Short.MAX_VALUE) throw new WireException(ints.length + " ints, over writeInts' 32,767");
        write.s(ints.length);
        for(int i : ints) write.i(i);
    }

    public static int[] readInts(Reads read){
        int[] out = new int[count(read, read.s(), 4, "int array")];
        for(int i = 0; i < out.length; i++) out[i] = read.i();
        return out;
    }

    public static void writeShorts(Writes write, short[] shorts){
        if(shorts.length > Short.MAX_VALUE) throw new WireException(shorts.length + " shorts, over writeShorts' 32,767");
        write.s(shorts.length);
        for(short s : shorts) write.s(s);
    }

    public static short[] readShorts(Reads read){
        short[] out = new short[count(read, read.s(), 2, "short array")];
        for(int i = 0; i < out.length; i++) out[i] = read.s();
        return out;
    }

    /** {@code writeIntSeq}: an i32 count. */
    public static void writeIntSeq(Writes write, int[] ints){
        write.i(ints.length);
        for(int i : ints) write.i(i);
    }

    public static int[] readIntSeq(Reads read){
        int[] out = new int[count(read, read.i(), 4, "IntSeq")];
        for(int i = 0; i < out.length; i++) out[i] = read.i();
        return out;
    }

    /** {@code writeStringArray}: u8 rows of u8 columns of {@code writeString}. Vanilla truncates larger counts. */
    public static void writeStringArray(Writes write, String[][] rows){
        if(rows.length > 255) throw new WireException(rows.length + " option rows, over 255");
        write.b(rows.length);
        for(String[] row : rows){
            if(row.length > 255) throw new WireException(row.length + " options in a row, over 255");
            write.b(row.length);
            for(String s : row) writeString(write, s);
        }
    }

    public static String[][] readStringArray(Reads read){
        String[][] rows = new String[read.ub()][];
        for(int i = 0; i < rows.length; i++){
            rows[i] = new String[read.ub()];
            for(int j = 0; j < rows[i].length; j++) rows[i][j] = readString(read);
        }
        return rows;
    }

    /** {@code writeItemStacks}: an i16 count of {@code writeItems}. */
    public static void writeItemStacks(Writes write, List<ItemStack> stacks){
        write.s(stacks.size());
        for(ItemStack stack : stacks) writeItems(write, stack);
    }

    public static List<ItemStack> readItemStacks(Reads read){
        int count = count(read, read.s(), 6, "item stacks");
        List<ItemStack> out = new ArrayList<>(count);
        for(int i = 0; i < count; i++) out.add(readItems(read));
        return out;
    }

    public static void writeLiquidStacks(Writes write, List<LiquidStack> stacks){
        write.s(stacks.size());
        for(LiquidStack stack : stacks){
            write.s(stack.liquid);
            write.f(stack.amount);
        }
    }

    public static List<LiquidStack> readLiquidStacks(Reads read){
        int count = count(read, read.s(), 6, "liquid stacks");
        List<LiquidStack> out = new ArrayList<>(count);
        for(int i = 0; i < count; i++) out.add(new LiquidStack(read.s(), read.f()));
        return out;
    }

    /**
     * Checks an array length read from the wire: vanilla's allocation fails on a negative one. A length the remaining
     * bytes can't hold fails too, as vanilla's read would at the end; checking first keeps Shard from allocating it.
     */
    public static int count(Reads read, int count, int elementBytes, String what){
        if(count < 0) throw new WireException(what + " of negative length " + count);
        if((long)count * elementBytes > read.remaining()) throw new WireException(what + " of " + count + " elements, past the end");
        return count;
    }

    //endregion
    //region entity fields

    public static void writeVec2(Writes write, Vec2 value){
        write.f(value == null ? 0 : value.x);
        write.f(value == null ? 0 : value.y);
    }

    public static Vec2 readVec2(Reads read){
        return new Vec2(read.f(), read.f());
    }

    /** {@code writeItems}: the item (-1 for none) and the amount. */
    public static void writeItems(Writes write, ItemStack stack){
        write.s(stack.item);
        write.i(stack.amount);
    }

    public static ItemStack readItems(Reads read){
        return new ItemStack(read.s(), read.i());
    }

    /** {@code writeAbilities}: u8 count, one f32 each. */
    public static void writeAbilities(Writes write, float[] data){
        write.b(data.length);
        for(float f : data) write.f(f);
    }

    /** {@code readAbilities(read, abilities)}: the count is a signed byte, and a negative one fails its allocation. */
    public static float[] readAbilities(Reads read){
        float[] out = new float[count(read, read.b(), 4, "abilities")];
        for(int i = 0; i < out.length; i++) out[i] = read.f();
        return out;
    }

    /** {@code writeMounts}: u8 count, then state, aim x and aim y each. */
    public static void writeMounts(Writes write, List<Mount> mounts){
        write.b(mounts.size());
        for(Mount m : mounts){
            write.b(m.state);
            write.f(m.aimX);
            write.f(m.aimY);
        }
    }

    /** {@code readMounts}: the count is a signed byte, and a negative one reads nothing. */
    public static List<Mount> readMounts(Reads read){
        int count = Math.max(0, read.b());
        List<Mount> out = new ArrayList<>(count);
        for(int i = 0; i < count; i++) out.add(new Mount(read.b(), read.f(), read.f()));
        return out;
    }

    /** {@code writeStatus}: effect and time, and for a dynamic effect a flag byte and the fields it flags. */
    public static void writeStatus(Writes write, StatusEntry status, WireContext context){
        write.s(status.effect);
        write.f(status.time);
        if(!context.statusDynamic(status.effect)) return;
        float[] values = {status.damageMultiplier, status.healthMultiplier, status.speedMultiplier, status.reloadMultiplier,
            status.buildSpeedMultiplier, status.dragMultiplier};
        int flags = 0;
        for(int i = 0; i < values.length; i++) if(values[i] != 1f) flags |= 1 << i;
        if(status.armorOverride >= 0f) flags |= 1 << 6;
        write.b(flags);
        for(float value : values) if(value != 1f) write.f(value);
        if(status.armorOverride >= 0f) write.f(status.armorOverride);
    }

    /** {@code readStatus}: an unknown effect fails, as vanilla's null effect does (TypeIO.java:1064). */
    public static StatusEntry readStatus(Reads read, WireContext context){
        int effect = read.s();
        float time = read.f();
        if(!context.contentExists(CONTENT_STATUS, effect)) throw new WireException("unknown status effect " + effect);
        if(!context.statusDynamic(effect)) return StatusEntry.of(effect, time);
        int flags = read.ub();
        float[] values = {1f, 1f, 1f, 1f, 1f, 1f, -1f};
        for(int i = 0; i < values.length; i++) if((flags & 1 << i) != 0) values[i] = read.f();
        return new StatusEntry(effect, time, values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
    }

    //endregion
    //region controllers

    public static void writeController(Writes write, Controller controller){
        switch(controller){
            case PlayerControl p -> {
                write.b(0);
                write.i(p.player);
            }
            case FormationControl f -> {
                write.b(1);
                write.i(f.ref);
            }
            case LogicControl l -> {
                write.b(3);
                write.i(l.processor);
            }
            case AssemblerControl a -> write.b(5);
            case OtherControl o -> write.b(o.type);
            case CommandControl c -> writeCommandControl(write, c);
        }
    }

    static void writeCommandControl(Writes write, CommandControl c){
        int type = c.type;
        if(type != 4 && type != 6 && type != 7 && type != 8 && type != 9) throw new WireException("no CommandAI layout " + type);
        write.b(type);
        write.bool(c.attack != null);
        write.bool(c.target != null);
        if(c.target != null){
            write.f(c.target.x);
            write.f(c.target.y);
        }
        if(c.attack != null){
            write.b(c.attack.kind);
            write.i(c.attack.ref);
        }
        if(type >= 6) write.b(c.command);
        if(type >= 7){
            if(c.queue.size() > 255) throw new WireException(c.queue.size() + " queued commands, over 255");
            write.b(c.queue.size());
            for(QueueEntry q : c.queue){
                write.b(q.kind);
                switch(q.kind){
                    case 0, 1 -> write.i(q.ref);
                    case 2 -> {
                        write.f(q.x);
                        write.f(q.y);
                    }
                    default -> {}
                }
            }
        }
        if(type == 8){
            if(c.stances.size() != 1) throw new WireException("a type 8 CommandAI carries one stance, not " + c.stances.size());
            write.b(c.stances.getFirst());
        }else if(type == 9){
            if(c.stances.size() > 255) throw new WireException(c.stances.size() + " stances, over 255");
            write.b(c.stances.size());
            for(int stance : c.stances) write.b(stance);
        }
    }

    /** {@code readController}: every layout vanilla reads, old saves' included. */
    public static Controller readController(Reads read){
        int type = read.b();
        return switch(type){
            case 0 -> new PlayerControl(read.i());
            case 1 -> new FormationControl(read.i());
            case 3 -> new LogicControl(read.i());
            case 4, 6, 7, 8, 9 -> readCommandControl(read, type);
            case 5 -> new AssemblerControl();
            default -> type == 2 ? OtherControl.GENERIC : new OtherControl(type);
        };
    }

    static CommandControl readCommandControl(Reads read, int type){
        boolean hasAttack = read.bool(), hasPos = read.bool();
        Vec2 target = hasPos ? new Vec2(read.f(), read.f()) : null;
        Attack attack = null;
        if(hasAttack){
            int kind = read.b();
            attack = new Attack(kind, read.i());
        }
        int command = type >= 6 ? read.b() : -1;
        List<QueueEntry> queue = new ArrayList<>();
        if(type >= 7){
            int length = read.ub();
            for(int i = 0; i < length; i++){
                int kind = read.b();
                queue.add(switch(kind){
                    case 0, 1 -> new QueueEntry(kind, read.i(), 0, 0);
                    case 2 -> new QueueEntry(kind, 0, read.f(), read.f());
                    default -> new QueueEntry(kind, 0, 0, 0);
                });
            }
        }
        List<Integer> stances = new ArrayList<>();
        if(type == 8){
            stances.add(read.ub());
        }else if(type == 9){
            int count = read.ub();
            for(int i = 0; i < count; i++) stances.add(read.ub());
        }
        return new CommandControl(type, target, attack, command, List.copyOf(queue), List.copyOf(stances));
    }

    //endregion
    //region plans

    /** {@code writePlan}: a placement always says it has a config, which readers take with {@code readObjectSafe}. */
    public static void writePlan(Writes write, Plan plan){
        write.b(plan.breaking ? 1 : 0);
        write.i(plan.pos);
        if(!plan.breaking){
            write.s(plan.block);
            write.b(plan.rotation);
            write.b(1);
            WireObjects.writeObjectSafe(write, plan.config);
        }
    }

    /**
     * {@code readPlan}. Returns null for a position outside the world: vanilla stops right after it (TypeIO.java:667),
     * so the next plan starts there. Any first byte but 1 is a placement, whose config is read with
     * {@code readObjectSafe} and kept only if the has-config byte is 1.
     */
    public static Plan readPlan(Reads read, WireContext context){
        int type = read.b();
        int pos = read.i();
        //quirk: a plan outside the world ends after its position
        if(!context.tileExists(pos)) return null;
        if(type == 1) return Plan.breaking(pos);
        int block = read.s();
        int rotation = read.b();
        boolean hasConfig = read.b() == 1;
        Object config = WireObjects.readObjectSafe(read, context);
        return new Plan(false, pos, block, rotation, hasConfig ? config : null);
    }

    /**
     * {@code getMaxPlans}: how many plans of a unit's queue go on the wire. At most 20, and the queue stops after the
     * plan whose {@code byte[]} and {@code String} configs take the total past 500 (string length in UTF-16 units).
     */
    public static int maxPlans(List<Plan> plans){
        int used = Math.min(plans.size(), MAX_SYNCED_PLANS), total = 0;
        for(int i = 0; i < used; i++){
            Object config = plans.get(i).config;
            if(config instanceof byte[] b) total += b.length;
            if(config instanceof String s) total += s.length();
            if(total > MAX_PLAN_CONFIG_BYTES) return i + 1;
        }
        return used;
    }

    /** {@code writePlansQueueNet}: -1 for null, else the plans {@link #maxPlans} keeps. */
    public static void writePlansQueueNet(Writes write, List<Plan> plans){
        if(plans == null){
            write.i(-1);
            return;
        }
        int used = maxPlans(plans);
        write.i(used);
        for(int i = 0; i < used; i++) writePlan(write, plans.get(i));
    }

    /**
     * {@code readPlansQueueNet}: -1 is null and more than 20 fails. Quirk: any other negative count is an empty queue.
     * Plans outside the world are dropped; vanilla queues a null that its users skip (NetServer.java:816).
     */
    public static List<Plan> readPlansQueueNet(Reads read, WireContext context){
        int used = read.i();
        if(used == -1) return null;
        if(used > MAX_SYNCED_PLANS) throw new WireException("plan queue of " + used + ", over " + MAX_SYNCED_PLANS);
        List<Plan> out = new ArrayList<>(Math.max(used, 0));
        for(int i = 0; i < used; i++){
            Plan plan = readPlan(read, context);
            if(plan != null) out.add(plan);
        }
        return out;
    }

    /**
     * {@code writeClientPlans}: an i16 count (0 for null), then each placement's x and y, block, a rotation byte only for
     * blocks that rotate, and its config if a client plan may carry it (else null).
     */
    public static void writeClientPlans(Writes write, List<ClientPlan> plans, WireContext context){
        if(plans == null){
            write.s(0);
            return;
        }
        write.s(plans.size());
        for(ClientPlan plan : plans){
            write.i(Point2.pack(plan.x, plan.y));
            write.s(plan.block);
            if(context.blockRotates(plan.block)) write.b(plan.rotation);
            WireObjects.writeObject(write, clientPlanConfig(plan.config) ? plan.config : null);
        }
    }

    /** {@code validClientPlanConfig}. */
    public static boolean clientPlanConfig(Object config){
        return config == null || config instanceof Number || config instanceof Boolean || config instanceof WireObjects.ContentRef;
    }

    /**
     * {@code readClientPlans}: 0 is null, and more than 1,000 or a negative count fails ({@code ensureCapacity}). Each
     * plan reads x and y as u16, a block that must exist, a rotation if it rotates and a config of tag 0-3, 5, 10 or 11.
     */
    public static List<ClientPlan> readClientPlans(Reads read, WireContext context){
        int amount = read.s();
        if(amount == 0) return null;
        if(amount > MAX_PREVIEW_PLANS) throw new WireException(amount + " plan previews, over " + MAX_PREVIEW_PLANS);
        if(amount < 0) throw new WireException(amount + " plan previews");
        List<ClientPlan> out = new ArrayList<>(amount);
        for(int i = 0; i < amount; i++){
            int x = read.us(), y = read.us(), block = read.us();
            if(!context.blockExists(block)) throw new WireException("plan preview with unknown block " + block);
            int rotation = context.blockRotates(block) ? read.b() : 0;
            int tag = read.b();
            Object config = switch(tag){
                case 0, 1, 2, 3, 5, 10, 11 -> WireObjects.readValue(read, tag, true, context);
                default -> throw new WireException("plan preview config of type " + tag);
            };
            out.add(new ClientPlan(x, y, block, rotation, config));
        }
        return out;
    }

    //endregion
    //region UI

    /**
     * {@code writeMenuResult}: the token, the result string and up to 500 entries. Keys and string values use
     * {@code writeUTF}, not {@code writeString}.
     */
    public static void writeMenuResult(Writes write, MenuResult result){
        write.l(result.token);
        writeString(write, result.result);
        write.s(Math.min(MENU_MAX_VALUES, result.values.size()));
        int i = 0;
        for(var entry : result.values.entrySet()){
            if(++i > MENU_MAX_VALUES) break;
            writeUtf(write, entry.getKey());
            switch(entry.getValue()){
                case Float f -> {
                    write.b(0);
                    write.f(f);
                }
                case Boolean b -> {
                    write.b(1);
                    write.bool(b);
                }
                case String s -> {
                    write.b(2);
                    writeUtf(write, s);
                }
                default -> throw new WireException("menu result value " + entry.getValue());
            }
        }
    }

    /**
     * {@code readMenuResult}. Quirk: each string's byte limit is the smaller of 1,000 and the characters left, and
     * what is left is counted in UTF-16 units, so multibyte text gets more bytes than ASCII.
     */
    public static MenuResult readMenuResult(Reads read){
        long token = read.l();
        int remaining = MENU_MAX_TOTAL_CHARS;
        String result = null;
        if(read.b() != 0){
            result = str(read, MENU_MAX_RESULT);
            remaining -= result.length();
        }
        int entries = read.us();
        if(entries > MENU_MAX_VALUES) throw new WireException(entries + " menu result entries, over " + MENU_MAX_VALUES);
        SequencedMap<String, Object> values = new LinkedHashMap<>();
        for(int i = 0; i < entries; i++){
            if(remaining <= 0) throw new WireException("menu result strings over " + MENU_MAX_TOTAL_CHARS + " characters");
            String key = str(read, Math.min(remaining, MENU_MAX_VALUE));
            remaining -= key.length();
            int type = read.b();
            Object value = switch(type){
                case 0 -> read.f();
                case 1 -> read.bool();
                case 2 -> {
                    if(remaining <= 0) throw new WireException("menu result strings over " + MENU_MAX_TOTAL_CHARS + " characters");
                    String s = str(read, Math.min(remaining, MENU_MAX_VALUE));
                    remaining -= s.length();
                    yield s;
                }
                default -> throw new WireException("menu result entry of type " + type);
            };
            //an ObjectMap: a repeated key replaces the value in place
            values.put(key, value);
        }
        return new MenuResult(token, result, values);
    }

    /**
     * {@code NodeBuilder.write}: u8 node type, u16 entry count, then u16 key, u8 tag and value each. Fails before
     * writing anything for a tree deeper than {@link #UI_MAX_DEPTH}.
     */
    public static void writeUiBuilder(Writes write, UiNode node){
        int depth = uiDepth(node);
        if(depth > UI_MAX_DEPTH) throw new WireException("UiBuilder tree " + depth + " deep, over " + UI_MAX_DEPTH);
        writeUiNode(write, node);
    }

    /** The depth of a UiBuilder tree, counting the root as 1, without recursing. */
    static int uiDepth(UiNode root){
        int deepest = 0;
        Deque<Map.Entry<UiNode, Integer>> pending = new ArrayDeque<>(List.of(Map.entry(root, 1)));
        while(!pending.isEmpty()){
            var next = pending.pop();
            deepest = Math.max(deepest, next.getValue());
            if(next.getValue() > UI_MAX_DEPTH) break;
            for(UiEntry entry : next.getKey().entries){
                if(entry.value instanceof UiNode child) pending.push(Map.entry(child, next.getValue() + 1));
            }
        }
        return deepest;
    }

    static void writeUiNode(Writes write, UiNode node){
        if(node.type < 0 || node.type >= UI_NODE_TYPES) throw new WireException("UiBuilder node type " + node.type);
        write.b(node.type);
        write.s(node.entries.size());
        for(UiEntry entry : node.entries){
            write.s(entry.key);
            switch(entry.value){
                case String s -> {
                    write.b(0);
                    writeUtf(write, s);
                }
                case Float f -> {
                    write.b(1);
                    write.f(f);
                }
                case Boolean b -> {
                    write.b(2);
                    write.bool(b);
                }
                case UiNode child -> {
                    write.b(3);
                    writeUiNode(write, child);
                }
                default -> throw new WireException("UiBuilder value " + entry.value);
            }
        }
    }

    /**
     * {@code NodeBuilder.read}: node types past {@code buttonTable} and keys past the last {@code UiKey} fail, and so does
     * a tree deeper than {@link #UI_MAX_DEPTH}.
     */
    public static UiNode readUiBuilder(Reads read){
        return readUiNode(read, 1);
    }

    static UiNode readUiNode(Reads read, int depth){
        if(depth > UI_MAX_DEPTH) throw new WireException("UiBuilder tree over " + UI_MAX_DEPTH + " deep");
        int type = read.ub();
        if(type >= UI_KEYS) throw new WireException("UiKey " + type + " out of range");
        if(type >= UI_NODE_TYPES) throw new WireException("UiKey " + type + " is not a node type");
        int count = read.us();
        List<UiEntry> entries = new ArrayList<>(Math.min(count, read.remaining() / 3));
        for(int i = 0; i < count; i++){
            int key = read.us();
            if(key >= UI_KEYS) throw new WireException("UiKey " + key + " out of range");
            int tag = read.ub();
            Object value = switch(tag){
                case 0 -> str(read, 0);
                case 1 -> read.f();
                case 2 -> read.bool();
                case 3 -> readUiNode(read, depth + 1);
                default -> throw new WireException("UiBuilder entry of type " + tag);
            };
            entries.add(new UiEntry(key, value));
        }
        return new UiNode(type, List.copyOf(entries));
    }

    /** {@code writeTraceInfo}: at most 12 past IPs and names. */
    public static void writeTraceInfo(Writes write, TraceInfo trace){
        writeString(write, trace.ip);
        writeString(write, trace.uuid);
        writeString(write, trace.locale);
        write.b(trace.modded ? 1 : 0);
        write.b(trace.mobile ? 1 : 0);
        write.i(trace.timesJoined);
        write.i(trace.timesKicked);
        writeStrings(write, trace.ips);
        writeStrings(write, trace.names);
    }

    static void writeStrings(Writes write, List<String> strings){
        int count = Math.min(strings.size(), TRACE_MAX_STRINGS);
        write.b(count);
        for(int i = 0; i < count; i++) writeString(write, strings.get(i));
    }

    public static TraceInfo readTraceInfo(Reads read){
        String ip = readString(read), uuid = readString(read), locale = readString(read);
        boolean modded = read.b() == 1, mobile = read.b() == 1;
        int joined = read.i(), kicked = read.i();
        return new TraceInfo(ip, uuid, locale, modded, mobile, joined, kicked, readStrings(read), readStrings(read));
    }

    static List<String> readStrings(Reads read){
        int count = read.ub();
        List<String> out = new ArrayList<>(count);
        for(int i = 0; i < count; i++) out.add(readString(read));
        return out;
    }

    //endregion
    //region JSON blobs

    /** {@code readRules}: more than 100,000 bytes fails. */
    public static final int MAX_RULES = 100_000;
    /** {@code readObjectives}: 60,000 bytes or more fails. */
    public static final int MAX_OBJECTIVES = 59_999;
    /** {@code readObjectiveMarker}: more than 40,000 bytes fails. */
    public static final int MAX_MARKER = 40_000;

    /** {@code writeRules}, {@code writeObjectives}, {@code writeObjectiveMarker}: an i32 length and UTF-8 JSON. */
    public static void writeJson(Writes write, String json){
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        write.i(bytes.length);
        write.b(bytes);
    }

    /** Reads an i32-length UTF-8 blob of at most {@code max} bytes. Its JSON dialect is {@code :compat}'s concern. */
    public static String readJson(Reads read, int max, String what){
        int length = read.i();
        if(length > max) throw new WireException(what + " of " + length + " bytes, over " + max);
        return new String(read.b(count(read, length, 1, what)), StandardCharsets.UTF_8);
    }

    public static String readRules(Reads read){
        return readJson(read, MAX_RULES, "rules");
    }

    public static String readObjectives(Reads read){
        return readJson(read, MAX_OBJECTIVES, "objectives");
    }

    public static String readObjectiveMarker(Reads read){
        return readJson(read, MAX_MARKER, "objective marker");
    }

    //endregion
    //region payloads

    /** {@code Payload.write} for a building; unit payloads go through the generated {@code EntityClasses}. */
    public static void writeBuildPayload(Writes write, BuildPayload payload){
        write.b(1);
        write.s(payload.block);
        write.b(payload.version);
        write.b(payload.body);
    }

    /** The rest of a block payload after its type byte: the block, the version, then the body {@link WireContext} reads. */
    public static BuildPayload readBuildPayload(Reads read, WireContext context){
        int block = read.s();
        int version = read.b();
        return new BuildPayload(block, version, context.readBuildingBody(read, block, version));
    }

    //endregion
}
