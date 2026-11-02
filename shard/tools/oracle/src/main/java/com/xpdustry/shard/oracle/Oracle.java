package com.xpdustry.shard.oracle;

import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.maps.Map;
import mindustry.net.*;
import mindustry.type.*;
import mindustry.world.*;
import com.xpdustry.shard.oracle.Vanilla.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Decoders: Shard's bytes, read by vanilla's own readers, as JSON-able views ({@link Json#write}). A decode error is a
 * {@link DecodeException} that names the section (or field, or entity) and the byte offset.
 *
 * <p>Decoding changes vanilla's global state exactly as a vanilla client would ({@code world}, {@code state},
 * {@code Groups}, {@code Vars.player}). After a decode, tests may also inspect those globals directly.
 */
public final class Oracle{
    private static final String writeDesc = "(Larc/util/io/Writes;)V";

    /** Who reads the packet: the vanilla client ({@code toClient}, S→C) or the vanilla server ({@code toServer}, C→S). */
    public enum Direction{
        toClient,
        toServer;

        NetMode reader(){
            return this == toClient ? NetMode.client : NetMode.server;
        }

        /** The mode of the side that writes packets going this way. */
        public NetMode writer(){
            return this == toClient ? NetMode.server : NetMode.client;
        }
    }

    public record Section(String name, int offset, int length){}

    /**
     * A decoded world stream (what a vanilla client has after {@code NetworkIO.loadWorld}). {@code contentHeader} lists
     * the content types the header remaps, with their entry count and the names vanilla does not know; empty means
     * vanilla ids. {@code floors}, {@code overlays} and {@code blocks} count tiles by name (a building counts every tile
     * it covers).
     */
    public record WorldView(
        boolean deflated, int rawLength, List<Section> sections, List<String> warnings,
        String rules, Object rulesParsed, String locales, java.util.Map<String, String> tags,
        int wave, float wavetime, double tick, long seed0, long seed1,
        EntityView player,
        java.util.Map<String, Object> contentHeader,
        int width, int height,
        java.util.Map<String, Integer> floors, java.util.Map<String, Integer> overlays, java.util.Map<String, Integer> blocks,
        List<BuildingView> buildings, List<EntityView> entities, List<Object> plans, int markers, List<String> customChunks){

        public String json(){
            return Json.write(this);
        }

        public Section section(String name){
            return sections.stream().filter(s -> s.name.equals(name)).findFirst().orElse(null);
        }

        public BuildingView building(int x, int y){
            return buildings.stream().filter(b -> b.x == x && b.y == y).findFirst().orElse(null);
        }
    }

    /**
     * A building as vanilla read it. {@code lastDisabler} is the position ({@code Tile.pos()}) of the processor that
     * disabled it, or -1, and {@code visibleFlags} the teams that see it in fog, both from {@code writeBase}.
     * {@code fields} is the state its block-specific class declares (below {@code Building}), e.g. a conveyor's
     * {@code ids}, {@code xs}, {@code ys} and {@code len}.
     */
    public record BuildingView(int x, int y, String block, String team, int rotation, float health, boolean enabled,
                               int lastDisabler, long visibleFlags, String className, int version, java.util.Map<String, Integer> items, java.util.Map<String, Float> liquids,
                               java.util.Map<String, Object> power, Object config, java.util.Map<String, Object> fields){}

    /** An entity: its fields in the order of the layout it was read with (save {@code write} or {@code writeSync}). */
    public record EntityView(int id, int classId, String className, java.util.Map<String, Object> fields){}

    /** One field of a generated packet: where it sits in the body and what vanilla read. */
    public record FieldView(String name, String codec, int offset, int length, Object value){}

    public record PacketView(int id, String packet, Direction direction, boolean allowed, int priority, int length,
                             List<FieldView> fields, List<String> warnings){
        public Object value(String field){
            return fields.stream().filter(f -> f.name.equals(field)).findFirst().map(FieldView::value).orElse(null);
        }

        public String json(){
            return Json.write(this);
        }
    }

    /** One entity of an entity snapshot, as {@code NetClient.readSyncEntity} handled it. */
    public record SyncedEntity(int index, int offset, int length, int id, int classId, String className,
                               boolean created, boolean added, java.util.Map<String, Object> fields){}

    public record EntitySnapshotView(int amount, int length, int trailingBytes, List<SyncedEntity> entities, List<String> warnings){
        public String json(){
            return Json.write(this);
        }
    }

    //region world stream

    /**
     * Decodes a world stream ({@code WorldStream} payload): zlib-deflated as sent, or raw. Two passes over the inflated
     * bytes: an instrumented copy of {@code NetworkIO.loadWorld} that calls the same vanilla readers section by section
     * (to find offsets, and to check that every building and entity chunk is read to its exact length, which vanilla
     * never checks), then vanilla's real {@code NetworkIO.loadWorld}, which leaves the world loaded. Offsets are in the
     * inflated bytes.
     */
    public static WorldView decodeWorldStream(byte[] deflatedOrRaw){
        return Vanilla.asClient(() -> decodeWorldStreamAsClient(deflatedOrRaw));
    }

    private static WorldView decodeWorldStreamAsClient(byte[] deflatedOrRaw){
        boolean deflated = deflatedOrRaw.length >= 2 && (deflatedOrRaw[0] & 0xff) == 0x78;
        byte[] raw = deflated ? inflate(deflatedOrRaw) : deflatedOrRaw;

        WorldMirror mirror = new WorldMirror(raw);
        List<String> warnings = new ArrayList<>();
        Throwable mirrorError = run(warnings, mirror::read);

        Counter counter = new Counter(raw);
        Throwable vanillaError = run(warnings, () -> NetworkIO.loadWorld(counter));
        Vanilla.runPosted();

        if(mirrorError != null){
            DecodeException e = mirrorError instanceof DecodeException d ? d
                : new DecodeException("world stream", mirror.section, mirror.sectionStart, mirror.in.position(), "unexpected error", mirrorError);
            if(vanillaError == null){
                e.addSuppressed(new IllegalStateException("vanilla's NetworkIO.loadWorld did not throw: it is lenient here, but the stream is still wrong"));
            }else{
                e.addSuppressed(vanillaError);
            }
            throw e;
        }
        if(vanillaError != null){
            Section at = mirror.sectionAt(counter.position());
            throw new DecodeException("world stream", at == null ? "?" : at.name, at == null ? 0 : at.offset, counter.position(),
                "vanilla's NetworkIO.loadWorld failed where the oracle's section reader did not", vanillaError);
        }
        warnings.addAll(mirror.warnings);
        return worldView(deflated, raw.length, mirror, warnings);
    }

    /** A view of the world vanilla currently has loaded, e.g. after a {@link Sim} step. */
    public static List<BuildingView> buildings(){
        List<BuildingView> out = new ArrayList<>();
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.isCenter()) out.add(building(tile.build));
        }
        return out;
    }

    public static BuildingView building(Building b){
        java.util.Map<String, Integer> items = new LinkedHashMap<>();
        if(b.items != null) b.items.each((item, amount) -> items.put(item.name, amount));
        java.util.Map<String, Float> liquids = new LinkedHashMap<>();
        if(b.liquids != null) b.liquids.each((liquid, amount) -> {
            if(amount != 0f) liquids.put(liquid.name, amount);
        });
        java.util.Map<String, Object> power = b.power == null ? null : Views.map("status", Views.of(b.power.status), "links", Views.of(b.power.links));
        Object config;
        try{
            config = Views.of(b.config(), 2);
        }catch(RuntimeException e){
            config = Views.map("$error", e.toString());
        }
        return new BuildingView(b.tileX(), b.tileY(), b.block.name, b.team.name, b.rotation, b.health, b.enabled,
            b.lastDisabler == null ? -1 : b.lastDisabler.pos(), b.visibleFlags, Views.className(b.getClass()), b.version(), items, liquids, power, config, Views.fields(b, Building.class, 2));
    }

    /** An entity with the fields of its save layout ({@code write}), as in a world stream or a save. */
    public static EntityView entity(Entityc entity){
        return entityView(entity, "write");
    }

    /** An entity with the fields of its sync layout ({@code writeSync}), as in an entity snapshot. */
    public static EntityView syncedEntity(Entityc entity){
        return entityView(entity, "writeSync");
    }

    /** Units in vanilla's world, by id. */
    public static List<EntityView> units(){
        List<EntityView> out = new ArrayList<>();
        Groups.unit.copy(new Seq<>()).sort(Structs.comparingInt(Unit::id)).each(u -> out.add(entity(u)));
        return out;
    }

    static EntityView entityView(Entityc entity, String method){
        Layouts.Layout layout = Layouts.of(entity.getClass(), method, writeDesc);
        java.util.Map<String, Object> fields = new LinkedHashMap<>();
        if(layout != null){
            for(String name : layout.fields()) fields.put(name, Views.of(field(entity, name), 2));
        }
        return new EntityView(entity.id(), entity.classId(), Views.className(entity.getClass()), fields);
    }

    static Object field(Object target, String name){
        for(Field f : Views.fieldsOf(target.getClass(), null)){
            if(f.getName().equals(name)){
                try{
                    return f.get(target);
                }catch(IllegalAccessException e){
                    return Views.map("$error", e.toString());
                }
            }
        }
        return Views.map("$missing", name);
    }

    private static WorldView worldView(boolean deflated, int rawLength, WorldMirror mirror, List<String> warnings){
        TreeMap<String, Integer> floors = new TreeMap<>(), overlays = new TreeMap<>(), blocks = new TreeMap<>();
        for(Tile tile : world.tiles){
            floors.merge(tile.floor().name, 1, Integer::sum);
            overlays.merge(tile.overlay().name, 1, Integer::sum);
            blocks.merge(tile.block().name, 1, Integer::sum);
        }

        List<EntityView> entities = new ArrayList<>();
        Seq<Entityc> all = new Seq<>();
        Groups.all.each(e -> !(e instanceof Building) && !(e instanceof Player) && e.serialize(), all::add);
        Groups.unit.each(u -> !all.contains(u, true), all::add);
        all.sort(Structs.comparingInt(Entityc::id)).each(e -> entities.add(entity(e)));

        List<Object> plans = new ArrayList<>();
        for(TeamData data : state.teams.present){
            if(data.plans.isEmpty()) continue;
            List<Object> list = new ArrayList<>();
            for(BlockPlan plan : data.plans){
                list.add(Views.map("x", plan.x, "y", plan.y, "rotation", plan.rotation, "block", plan.block == null ? null : plan.block.name, "config", Views.of(plan.config, 2)));
            }
            plans.add(Views.map("team", data.team.name, "plans", list));
        }

        return new WorldView(deflated, rawLength, mirror.sections, warnings,
            mirror.rules, parseArcJson(mirror.rules), mirror.locales, mirror.tags,
            state.wave, state.wavetime, state.tick, GlobalVars.rand.seed0, GlobalVars.rand.seed1,
            entity(player), mirror.header, world.width(), world.height(), floors, overlays, blocks,
            buildings(), entities, plans, state.markers.size(), mirror.customChunks);
    }

    /** The instrumented copy of {@code NetworkIO.loadWorld} (v160.7), one vanilla reader call per section. */
    private static final class WorldMirror{
        final byte[] raw;
        final Counter in;
        final DataInputStream data;
        final Reads reads;
        final List<Section> sections = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        String section;
        int sectionStart;

        String rules, locales;
        java.util.Map<String, String> tags;
        java.util.Map<String, Object> header = new LinkedHashMap<>();
        List<String> customChunks = new ArrayList<>();

        WorldMirror(byte[] raw){
            this.raw = raw;
            in = new Counter(raw);
            data = new DataInputStream(in);
            reads = new Reads(data);
        }

        void begin(String name){
            end();
            section = name;
            sectionStart = in.position();
        }

        void end(){
            if(section != null) sections.add(new Section(section, sectionStart, in.position() - sectionStart));
        }

        Section sectionAt(int offset){
            for(Section s : sections){
                if(offset >= s.offset && offset < s.offset + Math.max(s.length, 1)) return s;
            }
            return null;
        }

        DecodeException fail(String detail, Throwable cause){
            return new DecodeException("world stream", section, sectionStart, in.position(), detail, cause);
        }

        void read(){
            var writer = SaveIO.getSaveWriter();
            try{
                Time.clear();
                begin("data patches");
                writer.readDataPatches(data, new SaveReadState(world.context));

                begin("rules");
                rules = data.readUTF();
                state.rules = JsonIO.read(Rules.class, rules);

                begin("map locales");
                locales = data.readUTF();
                state.mapLocales = JsonIO.read(MapLocales.class, locales);

                begin("map tags");
                StringMap map = writer.readStringMap(data);
                state.map = new Map(map);
                tags = new TreeMap<>();
                for(var e : map.entries()) tags.put(e.key, e.value);

                begin("game state");
                state.wave = data.readInt();
                state.wavetime = data.readFloat();
                state.tick = data.readDouble();
                GlobalVars.rand.seed0 = data.readLong();
                GlobalVars.rand.seed1 = data.readLong();

                begin("player");
                Groups.clear();
                int id = data.readInt();
                player.reset();
                player.read(reads);
                player.id = id;
                player.add();

                begin("content header");
                writer.readContentHeader(data);
                header = contentHeader(raw, sectionStart, in.position());

                begin("map");
                SaveReadState saveState = new SaveReadState(world.context);
                readMap(saveState);

                begin("entity mapping");
                Prov<?>[] mapping = writer.readEntityMapping(data);

                begin("team plans");
                writer.readTeamBlocks(data);

                begin("world entities");
                readWorldEntities(mapping, saveState);

                begin("markers");
                writer.readMarkers(data);

                begin("custom chunks");
                readCustomChunks(writer);
                end();
                section = "end";
                sectionStart = in.position();

                if(in.position() != raw.length){
                    throw fail((raw.length - in.position()) + " trailing bytes after the last section", null);
                }
            }catch(DecodeException e){
                throw e;
            }catch(Throwable t){
                throw fail(truncated(t) ? "the stream ends inside this section" : "vanilla's reader failed", t);
            }finally{
                content.setTemporaryMapper(null);
            }
        }

        /** SaveVersion#readMap, plus id checks and building chunk length checks. */
        void readMap(SaveReadState state) throws IOException{
            var context = state.context;
            int width = data.readUnsignedShort();
            int height = data.readUnsignedShort();
            boolean generating = context.isGenerating();
            TreeMap<Integer, Integer> unknownFloors = new TreeMap<>(), unknownBlocks = new TreeMap<>();

            if(!generating) context.begin();
            Throwable failure = null;
            try{
                context.resize(width, height);

                for(int i = 0; i < width * height; i++){
                    int x = i % width, y = i / width;
                    short floorid = data.readShort();
                    short oreid = data.readShort();
                    int consecutives = data.readUnsignedByte();
                    if(content.block(floorid) == null) unknownFloors.merge((int)floorid, 1, Integer::sum);
                    if(content.block(oreid) == null) unknownFloors.merge((int)oreid, 1, Integer::sum);
                    if(content.block(floorid) == Blocks.air) floorid = Blocks.stone.id;

                    context.create(x, y, floorid, oreid, (short)0);
                    for(int j = i + 1; j < i + 1 + consecutives; j++){
                        context.create(j % width, j / width, floorid, oreid, (short)0);
                    }
                    i += consecutives;
                    if(i >= width * height) throw fail("floor run at tile " + (i - consecutives) + " covers " + (consecutives + 1) + " tiles, past the end of the " + width + "x" + height + " map", null);
                }

                for(int i = 0; i < width * height; i++){
                    short blockId = data.readShort();
                    Block block = content.block(blockId);
                    Tile tile = context.tile(i);
                    if(block == null){
                        unknownBlocks.merge((int)blockId, 1, Integer::sum);
                        block = Blocks.air;
                    }
                    boolean isCenter = true;
                    byte packed = data.readByte();
                    boolean hadEntity = (packed & 1) != 0, hadData = (packed & 4) != 0;
                    byte tileData = 0, floorData = 0, overlayData = 0;
                    int extraData = 0;

                    if(hadData){
                        tileData = data.readByte();
                        floorData = data.readByte();
                        overlayData = data.readByte();
                        extraData = data.readInt();
                    }
                    if(hadEntity) isCenter = data.readBoolean();

                    if(isCenter){
                        tile.setBlock(block);
                        if(tile.build != null){
                            if(!state.preview) state.allBuildings.add(tile.build);
                            tile.build.enabled = true;
                        }
                    }
                    if(hadData){
                        tile.data = tileData;
                        tile.floorData = floorData;
                        tile.overlayData = overlayData;
                        tile.extraData = extraData;
                        context.onReadTileData();
                    }

                    if(hadEntity){
                        if(isCenter){
                            int length = data.readInt(), start = in.position();
                            String where = "building " + block.name + " at (" + tile.x + ", " + tile.y + "), chunk of " + length + " bytes at byte " + start;
                            if(block.hasBuilding()){
                                try{
                                    byte revision = reads.b();
                                    tile.build.readAll(reads, revision);
                                }catch(Throwable t){
                                    throw fail(where + ": vanilla's readAll failed", t);
                                }
                                int read = in.position() - start;
                                if(read != length){
                                    throw fail(where + ": vanilla's readAll read " + read + " bytes (vanilla does not skip the rest of a chunk, so it would misread everything after)", null);
                                }
                            }else{
                                warnings.add(where + ": the block has no building, vanilla skips the chunk");
                                data.skipBytes(length);
                            }
                            context.onReadBuilding();
                        }
                    }else if(!hadData){
                        int consecutives = data.readUnsignedByte();
                        for(int j = i + 1; j < i + 1 + consecutives; j++){
                            if(j >= width * height) throw fail("block run at tile " + i + " covers " + (consecutives + 1) + " tiles, past the end of the map", null);
                            context.tile(j).setBlock(block);
                        }
                        i += consecutives;
                    }
                }
            }catch(Throwable t){
                failure = t;
                throw t;
            }finally{
                //as vanilla, end the load even on failure; but a half-read map fails in end() too, so keep the first error
                if(!generating){
                    try{
                        context.end();
                    }catch(Throwable t){
                        if(failure == null) throw t;
                        failure.addSuppressed(t);
                    }
                }
            }
            unknownFloors.forEach((id, count) -> warnings.add("map: unknown floor/overlay id " + id + " on " + count + " tiles"));
            unknownBlocks.forEach((id, count) -> warnings.add("map: unknown block id " + id + " on " + count + " tiles (vanilla uses air)"));
        }

        /** SaveVersion#readWorldEntities, plus chunk length checks. */
        void readWorldEntities(Prov<?>[] mapping, SaveReadState state) throws IOException{
            IntSet used = new IntSet();
            Seq<Entityc> reassign = new Seq<>();
            int amount = data.readInt();
            for(int j = 0; j < amount; j++){
                int length = data.readInt(), start = in.position();
                int typeid = reads.ub();
                if(mapping[typeid] == null){
                    warnings.add("world entities: entity #" + j + " has unknown class id " + typeid + ", vanilla skips it");
                    reads.skip(length - 1);
                    continue;
                }
                int id = reads.i();
                Entityc entity = (Entityc)mapping[typeid].get();
                String where = "entity #" + j + " (id " + id + ", class id " + typeid + " " + entity.getClass().getSimpleName() + "), chunk of " + length + " bytes at byte " + start;
                EntityGroup.checkNextId(id);
                entity.id(id);
                try{
                    entity.read(reads);
                }catch(Throwable t){
                    throw fail(where + ": vanilla's read failed", t);
                }
                int read = in.position() - start;
                if(read != length) throw fail(where + ": class id, id and read() took " + read + " bytes", null);
                if(used.add(id)){
                    entity.add();
                }else{
                    warnings.add("world entities: duplicate entity id " + id + ", vanilla gives it a new id");
                    reassign.add(entity);
                }
            }
            for(var ent : reassign){
                ent.id(EntityGroup.nextId());
                ent.add();
            }
            Groups.all.each(Entityc::afterReadAll);
            Groups.unit.each(Entityc::afterReadAll);
            state.allBuildings.each(Buildingc::afterReadAll);
        }

        @SuppressWarnings("unchecked")
        void readCustomChunks(SaveVersion writer) throws Exception{
            Field field = SaveVersion.class.getDeclaredField("customChunks");
            field.setAccessible(true);
            var chunks = (OrderedMap<String, SaveVersion.CustomChunk>)field.get(null);
            int amount = data.readInt();
            for(int i = 0; i < amount; i++){
                String name = data.readUTF();
                customChunks.add(name);
                var chunk = chunks.get(name);
                if(chunk != null){
                    writer.readChunk(data, chunk::read);
                }else{
                    writer.skipChunk(data);
                }
            }
        }
    }

    /** The content header's types, entry counts, and the names vanilla does not know (they map to null). */
    static java.util.Map<String, Object> contentHeader(byte[] raw, int from, int to) throws IOException{
        java.util.Map<String, Object> out = new LinkedHashMap<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw, from, to - from));
        int types = in.readUnsignedByte();
        for(int i = 0; i < types; i++){
            ContentType type = ContentType.all[in.readByte()];
            int total = in.readShort();
            List<String> unknown = new ArrayList<>();
            for(int j = 0; j < total; j++){
                String name = in.readUTF();
                if(content.getByName(type, type == ContentType.block ? SaveFileReader.fallback.get(name, name) : name) == null) unknown.add(j + ":" + name);
            }
            out.put(type.name(), Views.map("count", total, "unknown", unknown));
        }
        return out;
    }

    //endregion
    //region packets

    private static boolean truncated(Throwable t){
        for(; t != null; t = t.getCause()){
            if(t instanceof EOFException) return true;
        }
        return false;
    }

    /** Decodes a packet body, reading it on the side that accepts it (server-bound if only a server accepts it). */
    public static PacketView decodePacket(int id, byte[] payload){
        return decodePacket(id, payload, null);
    }

    /**
     * Decodes a packet body (no id, length or compression byte: see {@link #decodeFrame}) with vanilla's packet
     * registry. Generated packets are parsed by their own {@code handled()}; the field offsets come from replaying the
     * readers {@code handled()} calls, in order (recovered from its bytecode).
     * @param direction who reads it; null picks the side that accepts it ({@code toClient} for packets both accept)
     */
    public static PacketView decodePacket(int id, byte[] payload, Direction direction){
        return decode(id, payload, direction).view;
    }

    private record Decoded(PacketView view, Packet packet){}

    private static Decoded decode(int id, byte[] payload, Direction direction){
        Vanilla.boot();
        Packet packet;
        try{
            packet = Net.newPacket((byte)id);
        }catch(RuntimeException e){
            throw new DecodeException("packet #" + id, "id", 0, 0, "vanilla has no packet with this id", e);
        }
        String name = packet.getClass().getSimpleName();
        Direction dir = direction != null ? direction : packet.allow(true) && !packet.allow(false) ? Direction.toServer : Direction.toClient;
        boolean allowed = packet.allow(dir == Direction.toServer);
        String input = "packet " + name + "#" + id;

        Supplier<Decoded> read = () -> Vanilla.net(dir.reader(), () -> {
            List<String> warnings = new ArrayList<>();
            if(!allowed) warnings.add("vanilla's " + (dir == Direction.toServer ? "server" : "client") + " rejects this packet type (Packet.allow)");

            Counter counter = new Counter(payload);
            try{
                packet.read(new Reads(new DataInputStream(counter)), payload.length);
            }catch(Throwable t){
                throw new DecodeException(input, "body", 0, counter.position(), truncated(t) ? "the body ends inside this field" : "vanilla's read failed", t);
            }

            Layouts.Layout handled = Layouts.declared(packet.getClass(), "handled", "()V");
            if(handled == null){
                //framework packets parse in read()
                if(counter.position() != payload.length) warnings.add((payload.length - counter.position()) + " trailing bytes after the packet");
                List<FieldView> fields = new ArrayList<>();
                Layouts.Layout write = Layouts.declared(packet.getClass(), "write", writeDesc);
                List<String> names = write == null ? publicFields(packet) : write.fields();
                for(String field : names) fields.add(new FieldView(field, null, -1, -1, Views.of(field(packet, field), 2)));
                return new Decoded(new PacketView(id, name, dir, allowed, packet.getPriority(), payload.length, fields, warnings), packet);
            }

            List<FieldView> trace = new ArrayList<>();
            String traceProblem = trace(handled, payload, dir.reader(), trace);
            try{
                packet.handled();
            }catch(Throwable t){
                int at = baisPosition();
                FieldView field = trace.stream().filter(f -> at >= f.offset && at <= f.offset + Math.max(f.length, 0)).reduce((a, b) -> b).orElse(null);
                throw new DecodeException(input, field == null ? "body" : "field " + field.name, field == null ? 0 : field.offset, at, truncated(t) ? "the body ends inside this field" : "vanilla's handled() failed", t);
            }
            int consumed = baisPosition();
            if(consumed != payload.length) warnings.add((payload.length - consumed) + " trailing bytes after the last field");
            if(traceProblem != null) warnings.add("field offsets unavailable: " + traceProblem);

            List<FieldView> fields = new ArrayList<>();
            List<String> names = handled.fields();
            for(String field : names){
                FieldView traced = trace.stream().filter(f -> f.name.equals(field)).findFirst().orElse(null);
                fields.add(new FieldView(field, traced == null ? null : traced.codec, traced == null ? -1 : traced.offset,
                    traced == null ? -1 : traced.length, Views.of(field(packet, field), 2)));
            }
            return new Decoded(new PacketView(id, name, dir, allowed, packet.getPriority(), payload.length, fields, warnings), packet);
        });
        //the client reads with its local player installed (Loc.both readers, entity lookups); the server has none
        return dir == Direction.toClient ? Vanilla.asClient(read) : read.get();
    }

    /**
     * Decodes one serialized frame as Arc puts it on the wire (TCP without its u16 length prefix, or a UDP datagram):
     * {@code u8 id, u16 length, u8 compression, body}, LZ4-decompressing the body when needed.
     */
    public static PacketView decodeFrame(byte[] serialized, Direction direction){
        if(serialized.length < 4) throw new DecodeException("frame", "header", 0, serialized.length, "a frame has at least 4 bytes", null);
        if(serialized[0] == -2) throw new DecodeException("frame", "header", 0, 0, "this is an Arc framework message (id -2), not a packet", null);
        byte[] body;
        try{
            body = Wire.body(serialized);
        }catch(RuntimeException e){
            throw new DecodeException("frame", "body", 4, 4, "the body does not decompress", e);
        }
        return decodePacket(serialized[0] & 0xff, body, direction);
    }

    /**
     * Replays the reader calls of a generated {@code handled()} on the payload to find each field's offset.
     * Returns why it gave up, or null.
     */
    private static String trace(Layouts.Layout handled, byte[] payload, NetMode mode, List<FieldView> out){
        Counter counter = new Counter(payload);
        Reads reads = new Reads(new DataInputStream(counter));
        for(Layouts.Op op : handled.ops()){
            if(op.loop()) return "a loop in handled()";
            if(op.condition() != null){
                if(op.condition().equals("net.client()") && mode != NetMode.client) continue;
                if(op.condition().equals("net.server()") && mode != NetMode.server) continue;
                if(op.condition().equals("?")) return "a branch in handled()";
            }
            int start = counter.position();
            Object value;
            try{
                Class<?> owner = Class.forName(op.owner().replace('/', '.'), true, Oracle.class.getClassLoader());
                if(op.owner().equals(Layouts.reads) && op.descriptor().startsWith("()")){
                    value = owner.getMethod(op.method()).invoke(reads);
                }else if(op.descriptor().startsWith("(L" + Layouts.reads + ";)")){
                    value = owner.getMethod(op.method(), Reads.class).invoke(null, reads);
                }else{
                    return "cannot replay " + op.codec();
                }
            }catch(InvocationTargetException e){
                out.add(new FieldView(op.field(), op.codec(), start, counter.position() - start, Views.map("$error", DecodeException.describe(e.getCause()))));
                return null;
            }catch(ReflectiveOperationException e){
                return "cannot replay " + op.codec() + ": " + e;
            }
            out.add(new FieldView(op.field(), op.codec(), start, counter.position() - start, Views.of(value, 2)));
        }
        return null;
    }

    private static int baisPosition(){
        try{
            Field f = Packet.class.getDeclaredField("BAIS");
            f.setAccessible(true);
            return ((ReusableByteInStream)f.get(null)).position();
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    private static List<String> publicFields(Object o){
        List<String> out = new ArrayList<>();
        for(Field f : o.getClass().getFields()){
            if(!Modifier.isStatic(f.getModifiers())) out.add(f.getName());
        }
        return out;
    }

    //endregion
    //region entity snapshots

    /** Decodes the body of an {@code entitySnapshot} packet: {@code i16 amount, i16 length, data}. */
    public static EntitySnapshotView decodeEntitySnapshot(byte[] packetBody){
        Decoded decoded = decode(Net.getPacketId(new EntitySnapshotCallPacket()) & 0xff, packetBody, Direction.toClient);
        if(!decoded.view.warnings().isEmpty()){
            throw new DecodeException("entity snapshot", "packet", 0, packetBody.length, String.join("; ", decoded.view.warnings()), null);
        }
        var packet = (EntitySnapshotCallPacket)decoded.packet;
        return decodeEntitySnapshot(packet.amount, packet.data);
    }

    /**
     * Reads {@code amount} entities from snapshot data the way {@code NetClient.readSyncEntity} does, as a vanilla
     * client: an entity already in {@code Groups.sync} (or the local player, by id) is updated, a new one is created
     * from {@code EntityMapping} and added. Fields are listed in {@code writeSync} order with the values read. Offsets
     * are relative to {@code data}.
     */
    public static EntitySnapshotView decodeEntitySnapshot(int amount, byte[] data){
        return Vanilla.asClient(() -> Vanilla.net(NetMode.client, () -> {
            Counter counter = new Counter(data);
            DataInputStream input = new DataInputStream(counter);
            Reads read = new Reads(input);
            List<SyncedEntity> entities = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            for(int j = 0; j < amount; j++){
                int start = counter.position();
                String where = "entity #" + j;
                int id, typeId;
                try{
                    id = input.readInt();
                    typeId = input.readByte() & 0xff;
                }catch(IOException e){
                    throw new DecodeException("entity snapshot", where, start, counter.position(), "the header (i32 id, u8 class id) is cut off", e);
                }
                where += " (id " + id + ", class id " + typeId + ")";

                Syncc entity = Groups.sync.getByID(id);
                boolean add = false, created = false;
                if(entity == null && id == player.id()){
                    entity = player;
                    add = true;
                }
                if(entity == null){
                    Prov<?> prov = EntityMapping.map(typeId);
                    if(prov == null) throw new DecodeException("entity snapshot", where, start, counter.position(), "vanilla has no entity class with this id", null);
                    entity = (Syncc)prov.get();
                    entity.id(id);
                    if(!netClient.isEntityUsed(entity.id())) add = true;
                    created = true;
                }else if(entity.classId() != typeId){
                    warnings.add(where + ": the client already has this id as class id " + entity.classId() + " (" + entity.getClass().getSimpleName() + ") and reads it with that class's readSync");
                }

                try{
                    entity.readSync(read);
                }catch(Throwable t){
                    throw new DecodeException("entity snapshot", where + " " + entity.getClass().getSimpleName(), start, counter.position(), truncated(t) ? "the data ends inside this entity" : "vanilla's readSync failed", t);
                }
                if(created) entity.snapSync();
                if(add){
                    entity.add();
                    netClient.addRemovedEntity(entity.id());
                }
                if(created && !add) warnings.add(where + ": the client saw this id before, so it reads it but never adds it (a ghost)");
                EntityView view = syncedEntity(entity);
                entities.add(new SyncedEntity(j, start, counter.position() - start, id, typeId, view.className(), created, add, view.fields()));
            }
            Vanilla.runPosted();
            return new EntitySnapshotView(amount, data.length, data.length - counter.position(), entities, warnings);
        }));
    }

    //endregion
    //region helpers

    /** zlib-inflates a world stream, as {@code NetClient} does with an {@code InflaterInputStream}. */
    public static byte[] inflate(byte[] deflated){
        try(var in = new InflaterInputStream(new ByteArrayInputStream(deflated))){
            return in.readAllBytes();
        }catch(IOException e){
            throw new DecodeException("world stream", "zlib", 0, 0, "not a valid zlib stream", e);
        }
    }

    /** Arc's lenient JSON (unquoted keys, as {@code JsonIO} writes it) as maps and lists. */
    public static Object parseArcJson(String json){
        if(json == null) return null;
        try{
            return arcJson(new JsonReader().parse(json));
        }catch(RuntimeException e){
            return Views.map("$unparsed", json);
        }
    }

    private static Object arcJson(JsonValue v){
        if(v == null) return null;
        return switch(v.type()){
            case object -> {
                java.util.Map<String, Object> out = new LinkedHashMap<>();
                for(JsonValue c = v.child; c != null; c = c.next) out.put(c.name, arcJson(c));
                yield out;
            }
            case array -> {
                List<Object> out = new ArrayList<>();
                for(JsonValue c = v.child; c != null; c = c.next) out.add(arcJson(c));
                yield out;
            }
            case stringValue -> v.asString();
            case doubleValue -> v.asDouble();
            case longValue -> v.asLong();
            case booleanValue -> v.asBoolean();
            case nullValue -> null;
        };
    }

    /** Runs an action, collecting vanilla's warnings, and returns what it threw. */
    private static Throwable run(List<String> warnings, Runnable action){
        Throwable[] error = {null};
        warnings.addAll(Vanilla.logged(() -> {
            try{
                action.run();
            }catch(Throwable t){
                error[0] = t;
            }
        }));
        return error[0];
    }

    /** A byte array stream that tells how far it has been read. DataInputStream does not buffer, so this is exact. */
    static final class Counter extends ByteArrayInputStream{
        Counter(byte[] bytes){
            super(bytes);
        }

        int position(){
            return pos;
        }
    }

    //endregion

    private Oracle(){}
}
