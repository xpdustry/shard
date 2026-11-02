package com.xpdustry.shard.wire;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.KindKey;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.wire.v160_7.EntityClasses;
import com.xpdustry.shard.wire.v160_7.EntityClasses.*;
import mindustry.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 for the save revisions before v160.7's. Shard reads an old body to its end and writes it at the current revision;
 * vanilla reads both into fresh entities, which must then write the same bytes.
 * <ul>
 * <li>Vanilla-written samples: every entity of the bundled maps in the jar and of the saves in {@code resources/saves},
 *     in the save's world and content ids, and the unit payloads of their payload blocks and conveyors
 *     ({@link #PAYLOADS}). The saves are vanilla's test saves ({@code 77.msav}…, copied from Mindustry v160.7's
 *     {@code tests/src/test/resources}) and old servers' ({@code v105.msav}…, from {@code wire/scripts/old-saves.sh}:
 *     every unit type, payload units carrying a unit, a fire and a puddle). They cover the bodies in {@link #COVERED};
 *     the others were never in a release, or no release server at hand writes them.
 * <li>Every old body of every class: written from a random vanilla entity's fields in the schema's field order. Vanilla
 *     reading it to its end checks the lifted order against vanilla's own {@code read}.
 * </ul>
 */
class OldRevisionsTest{
    static final VanillaContext context = new VanillaContext();
    /** The old bodies the samples cover; one missing here lost its last sample. */
    static final Set<String> COVERED = Set.of(
        "BlockUnitUnit.0", "BlockUnitUnit.1", "BlockUnitUnit.2", "BlockUnitUnit.3", "LegsUnitLegacySpiroct.0", "LegsUnitLegacySpiroct.2",
        "MechUnit.0", "MechUnit.1", "MechUnit.2", "MechUnit.3", "MechUnit.4", "MechUnit.5", "MechUnit.6", "MechUnit.7",
        "MechUnitLegacyNova.0", "MechUnitLegacyNova.1", "PayloadUnit.0", "PayloadUnit.1", "PayloadUnit.2", "PayloadUnit.3",
        "PayloadUnit.4", "PayloadUnit.5", "PayloadUnitLegacyQuad.1", "PayloadUnitLegacyQuad.2", "Puddle.0",
        "UnitEntityLegacyAlpha.0", "UnitEntityLegacyAlpha.1", "UnitEntityLegacyAlpha.2", "UnitEntityLegacyAlpha.3",
        "UnitEntityLegacyMono.0", "UnitEntityLegacyMono.1", "UnitEntityLegacyPoly.0", "UnitEntityLegacyPoly.1");
    /**
     * The unit payloads' classes and revisions, from the bundled maps Shard loads. {@code hidden/230}'s rev 7 mech and
     * naval units kept it from loading before old revisions were read, and {@code hidden/16}, {@code 30} and {@code 191}'s
     * old payload units (a rev 5 {@code PayloadUnit} among them) kept their draws from being counted.
     */
    static final Set<String> PAYLOADS = Set.of("LegsUnit 7", "LegsUnit 9", "LegsUnitLegacySpiroct 6", "LegsUnitLegacySpiroct 8",
        "MechUnit 7", "MechUnit 9", "MechUnitLegacyNova 5", "MechUnitLegacyNova 7", "MechUnitLegacyPulsar 3",
        "MechUnitLegacyPulsar 5", "PayloadUnit 5", "PayloadUnit 7", "PayloadUnitLegacyQuad 8", "UnitEntity 7", "UnitEntity 9",
        "UnitEntityLegacyMono 6", "UnitEntityLegacyMono 8", "UnitEntityLegacyPoly 5", "UnitEntityLegacyPoly 7", "UnitWaterMove 7",
        "UnitWaterMove 9");
    static final int SEEDS = 4;

    record Sample(String name, String host, byte[] bytes){}

    @Test
    void vanillaWrittenSamples() throws Exception{
        Map<String, String> bodies = oldBodies();
        SortedMap<String, Integer> covered = new TreeMap<>();
        List<String> failures = new ArrayList<>(), skipped = new ArrayList<>();
        int entities = 0;
        for(Sample sample : samples()){
            SaveFile file = new MsavReader(Epoch.vanilla()).read(sample.bytes());
            if(file.entities().isEmpty()) continue;
            Vanilla.host(sample.host());
            Map<Integer, Integer> classes = new HashMap<>();
            for(var entry : file.entityMapping()) classes.put(entry.id(), ((Entityc)EntityMapping.map(entry.name()).get()).classId() & 0xFF);
            content.setTemporaryMapper(mapper(file.header()));
            try{
                for(var chunk : file.entities()){
                    int classId = classes.getOrDefault(chunk.classId(), chunk.classId());
                    EntityClass type = EntityClasses.get(classId);
                    byte[] body = chunk.body();
                    int revision = (short)((body[0] & 0xFF) << 8 | body[1] & 0xFF);
                    String where = sample.name() + " " + (type == null ? "#" + classId : type.name()) + " revision " + revision;
                    if(EntityMapping.map(classId) == null){
                        //an old build's class v160.7 no longer has: vanilla skips its chunk (SaveVersion.java:503), Shard has no layout
                        if(type != null) failures.add(where + ": Shard has a layout for a class vanilla skips");
                        skipped.add(where);
                        continue;
                    }
                    entities++;
                    try{
                        compare(classId, body);
                        if(type != null && revision != type.revision()) covered.merge(bodies.get(type.name() + " " + revision), 1, Integer::sum);
                    }catch(AssertionError | RuntimeException e){
                        failures.add(where + ": " + e.getMessage());
                    }
                }
            }finally{
                content.setTemporaryMapper(null);
            }
        }
        System.out.println(entities + " entities; old bodies covered: " + covered + "; skipped: " + skipped);
        assertEquals(List.of(), failures.stream().limit(20).toList(), failures.size() + " failures");
        assertEquals(new TreeSet<>(COVERED), new TreeSet<>(covered.keySet()), "old bodies with a sample");
    }

    static final KindKey UNIT_FACTORY = KindKey.of("unit-factory"), RECONSTRUCTOR = KindKey.of("reconstructor");

    @Test
    void unitPayloadSamples() throws Exception{
        SortedMap<String, Integer> covered = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for(Sample sample : samples()){
            if(!sample.host().startsWith("maps/")) continue;
            SaveFile file = new MsavReader(Epoch.vanilla()).read(sample.bytes());
            var ids = file.header().mapping(Epoch.vanilla());
            List<byte[]> payloads = new ArrayList<>();
            for(var entry : file.map().buildings().entrySet()){
                // the unit factories' and reconstructors' bodies, read from the save's chunks: the kinds whose
                // saved units these samples are, whatever Shard ports
                int block = ids.toCurrent(com.xpdustry.shard.content.ContentType.BLOCK, file.map().blocks()[entry.getKey()]);
                if(block < 0) continue;
                com.xpdustry.shard.content.BlockDef def = Epoch.vanilla().block(block);
                if(!def.baked() || !(def.isA(UNIT_FACTORY) || def.isA(RECONSTRUCTOR))) continue;
                var read = new com.xpdustry.shard.common.io.Reads(entry.getValue().body());
                BuildingBase.skip(new Tape(read), def);
                byte[] body = Arrays.copyOfRange(entry.getValue().body(), read.position(), entry.getValue().body().length);
                //Payload.write follows PayloadBlockBuild's vector and rotation
                payloads.add(Arrays.copyOfRange(body, 12, body.length));
            }
            if(payloads.isEmpty()) continue;
            Vanilla.host(sample.host());
            content.setTemporaryMapper(mapper(file.header()));
            try{
                for(byte[] payload : payloads){
                    var read = new com.xpdustry.shard.common.io.Reads(payload);
                    if(!read.bool() || read.b() != 0) continue; //empty, or a block payload
                    int classId = read.b(), start = read.position();
                    EntityClasses.readSave(read, classId, context.source(payload));
                    byte[] body = Arrays.copyOfRange(payload, start, read.position());
                    String pair = EntityClasses.get(classId).name() + " " + (short)((body[0] & 0xFF) << 8 | body[1] & 0xFF);
                    try{
                        compare(classId, body);
                        covered.merge(pair, 1, Integer::sum);
                    }catch(AssertionError | RuntimeException e){
                        failures.add(sample.name() + " " + pair + ": " + e.getMessage());
                    }
                }
            }finally{
                content.setTemporaryMapper(null);
            }
        }
        System.out.println("unit payloads: " + covered);
        assertEquals(List.of(), failures.stream().limit(20).toList(), failures.size() + " failures");
        assertEquals(new TreeSet<>(PAYLOADS), new TreeSet<>(covered.keySet()), "unit payload samples");
    }

    @Test
    void everyOldBody() throws Exception{
        Map<String, List<String[]>> fields = oldBodyFields();
        Map<String, String> bodies = oldBodies();
        Randoms random = Randoms.world(45);
        List<String> failures = new ArrayList<>();
        int cases = 0;
        for(EntityClass type : EntityClasses.ALL){
            for(int revision = 0; revision < type.revision(); revision++){
                String body = bodies.get(type.name() + " " + revision);
                if(body == null || body.equals("save")) continue;
                for(int seed = 0; seed < SEEDS; seed++){
                    cases++;
                    try{
                        compare(type.id(), synthesize(random, type.id(), revision, fields.get(body)));
                    }catch(AssertionError | RuntimeException e){
                        failures.add(type.name() + " revision " + revision + " (" + body + "): " + e.getMessage());
                    }
                }
            }
        }
        System.out.println(cases + " synthesized old bodies");
        assertEquals(List.of(), failures.stream().limit(20).toList(), failures.size() + " failures");
        assertEquals(151 * SEEDS, cases);
    }

    /** An old body with a random vanilla entity's fields, as Shard reads them from its current save. */
    static byte[] synthesize(Randoms random, int classId, int revision, List<String[]> fields) throws ReflectiveOperationException{
        Entityc entity = random.entity(classId);
        byte[] current = Golden.bytes(entity::write);
        WireEntity state = EntityClasses.readSave(new com.xpdustry.shard.common.io.Reads(current), classId, context.source(current));
        var write = new com.xpdustry.shard.common.io.Writes();
        write.s(revision);
        for(String[] field : fields){
            Object value = field[0].equals("_") ? dropped(random, field[1]) : state.getClass().getField(field[0]).get(state);
            write(write, field[1], value);
        }
        return write.toByteArray();
    }

    /** A value for a field an old body reads and drops. */
    static Object dropped(Randoms random, String codec){
        return switch(codec){
            case "f32" -> random.f();
            case "bool" -> random.rnd.nextBoolean();
            case "i16" -> (short)random.rnd.nextInt();
            case "i32" -> random.rnd.nextInt();
            default -> throw new IllegalArgumentException("no dropped " + codec);
        };
    }

    /** Writes a value with a schema codec, as the generated layouts do. */
    @SuppressWarnings("unchecked")
    static void write(com.xpdustry.shard.common.io.Writes write, String codec, Object value){
        if(codec.startsWith("seq<")){
            List<Object> items = (List<Object>)value;
            write.i(items.size());
            for(Object item : items) write(write, codec.substring(4, codec.length() - 1), item);
            return;
        }
        switch(codec){
            case "f32" -> write.f((float)value);
            case "f64" -> write.d((double)value);
            case "bool" -> write.bool((boolean)value);
            case "i8" -> write.b(((Number)value).byteValue());
            case "i16" -> write.s(((Number)value).shortValue());
            case "i32", "Building", "Tile", "Color" -> write.i((int)value);
            case "ref<unit>", "ref<liquid>", "ref<weather>", "ref<block>" -> write.s((short)value);
            case "Team" -> Codecs.writeTeam(write, (int)value);
            case "Command" -> Codecs.writeCommand(write, (int)value);
            case "Unit" -> Codecs.writeUnit(write, (Codecs.UnitRef)value);
            case "String" -> Codecs.writeString(write, (String)value);
            case "Vec2" -> Codecs.writeVec2(write, (Codecs.Vec2)value);
            case "Items" -> Codecs.writeItems(write, (Codecs.ItemStack)value);
            case "Abilities" -> Codecs.writeAbilities(write, (float[])value);
            case "Mounts" -> Codecs.writeMounts(write, (List<Codecs.Mount>)value);
            case "Controller" -> Codecs.writeController(write, (Codecs.Controller)value);
            case "Plan" -> Codecs.writePlan(write, (Codecs.Plan)value);
            case "Status" -> Codecs.writeStatus(write, (Codecs.StatusEntry)value, context);
            case "Payload" -> EntityClasses.writePayload(write, (Codecs.Payload)value, context);
            default -> throw new IllegalArgumentException("no writer for " + codec);
        }
    }

    /** Shard reads the old body to its end and writes it at the current revision; vanilla reads both alike. */
    static void compare(int classId, byte[] old){
        var read = new com.xpdustry.shard.common.io.Reads(old);
        WireEntity state = EntityClasses.readSave(read, classId, context.source(old));
        assertEquals(old.length, read.position(), "bytes left after Shard's read");
        var write = new com.xpdustry.shard.common.io.Writes();
        EntityClasses.writeSave(write, classId, state, context);
        byte[] current = write.toByteArray();

        byte[] fromOld = vanillaRoundTrip(classId, old), fromCurrent = vanillaRoundTrip(classId, current);
        assertArrayEquals(fromOld, fromCurrent, () -> "vanilla reads the old body and Shard's current one differently"
            + "\nold    =" + Hex.of(old) + "\nShard  =" + Hex.of(current) + "\nvanilla=" + Hex.of(fromOld) + "\nvia Shard=" + Hex.of(fromCurrent));
    }

    /**
     * Vanilla reads {@code body} into a fresh entity, to its end, and writes it at the current revision. A fresh weather
     * blows in a random direction, which an old body without one keeps; Shard's state starts with no wind, as here.
     */
    static byte[] vanillaRoundTrip(int classId, byte[] body){
        var input = new ByteArrayInputStream(body);
        Entityc entity = (Entityc)EntityMapping.map(classId).get();
        if(entity instanceof WeatherState weather) weather.windVector.setZero();
        entity.read(new arc.util.io.Reads(new DataInputStream(input)));
        assertEquals(0, input.available(), "bytes left after vanilla's read");
        return Golden.bytes(entity::write);
    }

    /** The save's content ids, as {@code readContentHeader} maps them while vanilla loads it. */
    static MappableContent[][] mapper(ContentHeader header){
        MappableContent[][] map = new MappableContent[ContentType.all.length][0];
        for(ContentType type : ContentType.all){
            if(type.ordinal() >= ContentHeader.TYPES) continue;
            map[type.ordinal()] = header.names(type.ordinal()).stream().map(name -> content.getByName(type, name)).toArray(MappableContent[]::new);
        }
        return map;
    }

    /** {@code "Class revision" -> body}, from the entities' {@code old} maps in the schema. */
    static Map<String, String> oldBodies() throws IOException{
        Map<String, String> bodies = new HashMap<>();
        Pattern entity = Pattern.compile("class: (\\w+),.* old: \\{([^}]*)}"), pair = Pattern.compile("(\\d+): ([\\w.]+)");
        for(String line : Files.readAllLines(Path.of("schema/v160.7.yaml"))){
            Matcher m = entity.matcher(line);
            if(!m.find()) continue;
            Matcher p = pair.matcher(m.group(2));
            while(p.find()) bodies.put(m.group(1) + " " + p.group(1), p.group(2));
        }
        return bodies;
    }

    /** Each old body's fields, {@code {name, codec}} in read order, from the layouts' {@code old} in the schema. */
    static Map<String, List<String[]>> oldBodyFields() throws IOException{
        Map<String, List<String[]>> bodies = new HashMap<>();
        Pattern field = Pattern.compile("^        - \\{name: \"?([^\",]+)\"?, codec: ([^,}]+)");
        boolean old = false;
        List<String[]> current = null;
        for(String line : Files.readAllLines(Path.of("schema/v160.7.yaml"))){
            if(line.equals("entities:")) break;
            if(line.matches("^ {2}\\w+:$") || line.matches("^ {4}(sync|save|defaults):.*")){
                old = false;
                current = null;
            }else if(line.equals("    old:")){
                old = true;
            }else if(old && line.matches("^ {6}[\\w.]+:$")){
                bodies.put(line.trim().replace(":", ""), current = new ArrayList<>());
            }else if(current != null){
                Matcher m = field.matcher(line);
                if(m.find()) current.add(new String[]{m.group(1), m.group(2)});
            }
        }
        return bodies;
    }

    /** The jar's bundled maps, then vanilla's test saves. */
    static List<Sample> samples() throws Exception{
        List<Sample> samples = new ArrayList<>();
        Path jar = Path.of(Vars.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try(var zip = new ZipFile(jar.toFile())){
            for(ZipEntry entry : Collections.list(zip.entries())){
                if(!entry.getName().startsWith("maps/") || !entry.getName().endsWith(".msav")) continue;
                try(var in = zip.getInputStream(entry)){
                    samples.add(new Sample(entry.getName(), entry.getName(), in.readAllBytes()));
                }
            }
        }
        URL saves = OldRevisionsTest.class.getResource("/saves");
        try(Stream<Path> files = Files.list(Path.of(Objects.requireNonNull(saves).toURI()))){
            for(Path file : files.sorted().toList()){
                samples.add(new Sample("saves/" + file.getFileName(), file.toString(), Files.readAllBytes(file)));
            }
        }
        return samples;
    }
}
