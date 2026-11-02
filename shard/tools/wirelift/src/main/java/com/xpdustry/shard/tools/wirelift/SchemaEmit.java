package com.xpdustry.shard.tools.wirelift;

import com.xpdustry.shard.tools.wirelift.Schema.*;
import com.xpdustry.shard.tools.wirelift.WireLift.*;

import java.lang.classfile.*;
import java.util.*;
import java.util.regex.*;

import static com.xpdustry.shard.tools.wirelift.WireLift.*;

/**
 * Turns lifted entity and packet bodies into the {@link Schema} (proto 25 {@code SchemaEmit}). Entity IO is generated
 * by vanilla's annotation processor, so it only uses five patterns; anything else becomes a problem, never a guess:
 * <pre>
 * prim field | TypeIO.writeX(field) | content.id-or-minus-one (ref) | count + looped element (seq) | leading constant (save revision)
 * </pre>
 */
final class SchemaEmit{
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
        "Lmindustry/type/UnitType;", "unit", "Lmindustry/world/Block;", "block", "Lmindustry/type/Item;", "item",
        "Lmindustry/type/Liquid;", "liquid", "Lmindustry/type/Weather;", "weather", "Lmindustry/type/StatusEffect;", "status",
        "Lmindustry/entities/bullet/BulletType;", "bullet");
    /** Enums read as {@code X.all[ordinal]} by TypeIO or a packet: the ordinal range is part of what a reader accepts. */
    private static final Map<String, String> ENUMS = new TreeMap<>(Map.of(
        "ContentType", "mindustry/ctype/ContentType",
        "AdminAction", "mindustry/net/Packets$AdminAction",
        "KickReason", "mindustry/net/Packets$KickReason",
        "LAccess", "mindustry/logic/LAccess",
        "LMarkerControl", "mindustry/logic/LMarkerControl",
        "UiKey", "mindustry/ui/builder/UiKey"));

    private final WireLift lift;
    private final List<String> problems = new ArrayList<>();

    private SchemaEmit(WireLift lift){
        this.lift = lift;
    }

    static Schema emit(WireLift lift){
        return new SchemaEmit(lift).schema();
    }

    private Schema schema(){
        Map<String, List<String>> enums = new LinkedHashMap<>();
        ENUMS.forEach((name, cls) -> enums.put(name, lift.enumConstants(cls)));

        // 1. per class: its writeSync fields (by the class that declares writeSync) and its save write()
        record Lifted(int id, String cls, List<Field> sync, String syncOwner, int revision, List<Field> save, SortedMap<Integer, List<Field>> reads,
                      SortedMap<String, String> defaults, boolean serialize, boolean unit){}
        List<Lifted> lifted = new ArrayList<>();
        for(var e : lift.classes.entrySet()){
            String cls = e.getKey();
            ClassModel model = e.getValue();
            if(!cls.startsWith("mindustry/gen/") || !concrete(model) || lift.method(cls, "classId", "()I") == null) continue;
            int id = Integer.parseInt(constReturn(model, "classId", "-1"));
            String simple = simple(cls);
            List<Field> sync = null;
            String owner = lift.owner(cls, "writeSync", WRITE);
            MethodModel writeSync = lift.resolve(cls, "writeSync", WRITE);
            if(writeSync != null && !cls.equals(BUILDING)){
                List<Field> fields = fields(owner, lift.lift(owner, writeSync), simple + ".writeSync");
                if(!fields.isEmpty()) sync = flags(owner, fields);
            }
            int revision = -1;
            List<Field> save = null;
            SortedMap<Integer, List<Field>> reads = new TreeMap<>();
            MethodModel write = lift.method(cls, "write", WRITE);
            if(write != null){
                List<Field> fields = fields(cls, lift.lift(cls, write), simple + ".write");
                if(!fields.isEmpty() && fields.getFirst().name().equals("$version")){
                    revision = Integer.parseInt(fields.removeFirst().codec());
                    save = fields;
                    reads = revisions(cls, simple + ".read");
                    if(!save.equals(reads.get(revision))){
                        problems.add(simple + ".read: revision " + revision + " reads " + compact(reads.get(revision)) + ", but write() writes " + compact(save));
                    }
                }
            }
            lifted.add(new Lifted(id, simple, sync, simple(owner), revision, save, reads, defaults(cls, sync, save), constReturn(model, "serialize", "?").equals("1"),
                lift.isSubclass(cls, "mindustry/gen/Unit")));
        }
        lifted.sort(Comparator.comparingInt(Lifted::id));

        // 2. a layout per distinct writeSync body, named after its most generic member (the plain UnitEntity body has 15
        //    classes, each with its own copy of writeSync); its classes must agree on the save body too
        Map<List<Field>, String> names = new LinkedHashMap<>();
        Map<String, List<String>> members = new TreeMap<>();
        for(Lifted l : lifted){
            if(l.sync == null) continue;
            names.putIfAbsent(l.sync, l.syncOwner);
            members.computeIfAbsent(names.get(l.sync), k -> new ArrayList<>()).add(l.cls);
        }
        names.replaceAll((fields, name) -> members.get(name).contains("UnitEntity") ? "UnitEntity" : name);
        //    old revisions' bodies are shared within a layout, named after the first class (by id) and revision that wrote one
        Map<String, Layout> layouts = new TreeMap<>();
        List<Entity> entities = new ArrayList<>();
        for(Lifted l : lifted){
            String name = l.sync != null ? names.get(l.sync) : l.save != null ? l.cls : null;
            SortedMap<Integer, String> old = new TreeMap<>();
            if(name != null){
                Layout previous = layouts.get(name);
                if(previous == null){
                    layouts.put(name, previous = new Layout(name, l.sync, l.save, new LinkedHashMap<>(), l.defaults));
                }else if(!Objects.equals(previous.save(), l.save)){
                    problems.add(l.cls + ": its save body differs from the rest of layout " + name);
                }else if(!previous.defaults().equals(l.defaults)){
                    problems.add(l.cls + ": its constructor defaults " + l.defaults + " differ from the rest of layout " + name + "'s " + previous.defaults());
                }
                for(var r : l.reads.entrySet()){
                    if(r.getKey() == l.revision) continue;
                    String body = r.getValue().equals(previous.save()) ? Schema.SAVE : null;
                    for(var known : previous.old().entrySet()) if(known.getValue().equals(r.getValue())) body = known.getKey();
                    if(body == null) previous.old().put(body = l.cls + "." + r.getKey(), r.getValue());
                    old.put(r.getKey(), body);
                }
            }
            entities.add(new Entity(l.id, l.cls, name, l.revision, old, l.serialize, l.unit));
        }

        List<Packet> packets = new ArrayList<>();
        for(PacketLift p : lift.packets()){
            String name = p.simpleName().replace("CallPacket", "");
            boolean framework = p.direction().equals("framework");
            if(!framework && !symmetric(p.write(), p.read())){
                problems.add(name + ": the read order differs from the write order beyond Object -> ObjectSafe: " + p.read());
            }
            if(!framework && !p.direction().equals(p.accepts())){
                problems.add(name + ": handled " + p.direction() + " but allow() accepts " + p.accepts());
            }
            if(p.accepts().equals("none")) problems.add(name + ": allow() accepts it on neither side");
            packets.add(new Packet(p.id(), name, p.accepts(), framework, p.priority(), p.reliable(), readTypes(p.write(), p.read())));
        }

        // 3. the entity classes each cast of a read entity lets through
        Map<String, List<Integer>> casts = new TreeMap<>();
        for(Packet p : packets){
            Matcher m = CAST.matcher(p.layout());
            while(m.find()){
                String type = lift.entityCasts.get(m.group(1));
                if(type == null){
                    problems.add(p.name() + ": a cast to " + m.group(1) + " that was not lifted");
                    continue;
                }
                casts.computeIfAbsent(m.group(1), k -> entities.stream().filter(e -> lift.assignable("mindustry/gen/" + e.className(), type)).map(Entity::id).toList());
            }
        }
        return new Schema(lift.build, enums, new LinkedHashMap<>(layouts), entities, casts, packets, List.copyOf(problems));
    }

    /**
     * The values a fresh entity's fields hold that differ from a zeroed state: the non-zero constants its constructor
     * stores ({@code updateBuilding = true}), and -1 for content it leaves null, which {@code write()} writes as -1. An
     * old revision leaves the fields it lacks at these, as vanilla reads into a fresh entity.
     */
    private SortedMap<String, String> defaults(String cls, List<Field> sync, List<Field> save){
        Map<String, String> stores = lift.constructorStores(cls);
        SortedMap<String, String> defaults = new TreeMap<>();
        for(List<Field> fields : Arrays.asList(sync, save)){
            if(fields == null) continue;
            for(Field f : fields){
                String value = stores.get(f.name());
                if(value == null && f.codec().startsWith("ref<")){
                    defaults.put(f.name(), "-1");
                }else if(value != null && !value.equals("?") && Double.parseDouble(value) != 0){
                    defaults.put(f.name(), lift.fieldDescriptor(cls, f.name()).equals("Z") ? "true" : value);
                }
            }
        }
        return defaults;
    }

    /** A read entity cast to a type in a packet layout: {@code Entity<Player>}. */
    private static final Pattern CAST = Pattern.compile("Entity<(\\w+)>");

    /** A read item without what only the read side shows: Object is read as ObjectSafe, and a read entity may be cast. */
    private static String untyped(String read){
        return read.replace("ObjectSafe ", "Object ").replaceAll("Entity<\\w+>", "Entity");
    }

    /**
     * Generated packets write Object params with writeObject but read them with readObjectSafe (client limits), and
     * cast read entities to the parameter's type. The write order alone cannot tell, so take the codec name from the
     * read order where the two differ only by that.
     */
    static String readTypes(String write, String read){
        String[] w = write.split(", "), r = read.split(", ");
        if(w.length != r.length) return write;
        for(int i = 0; i < w.length; i++){
            if(untyped(r[i]).equals(w[i])) w[i] = r[i];
        }
        return String.join(", ", w);
    }

    /** handled() reads what write() writes: the sender guard tests the other side, and the read side adds types. */
    private static boolean symmetric(String write, String read){
        return untyped(read.replace("net.client()", "net.server()")).equals(write);
    }

    /** Recognises the five EntityIO patterns in a lifted write body. */
    private List<Field> fields(String cls, Lifted l, String where){
        List<Field> out = new ArrayList<>();
        List<Op> ops = l.ops.stream().filter(o -> o.kind.equals("emit") || o.kind.equals("delegate")).toList();
        for(int i = 0; i < ops.size(); i++){
            Op op = ops.get(i);
            String arg = op.arg, codec = op.codec.replace("TypeIO.write", "");
            if(l.looped(op.index)) continue; // consumed by the count before it
            if(op.kind.equals("delegate")){
                problems.add(where + ": delegate " + op.codec);
            }else if(arg.matches("-?\\d+") && out.isEmpty()){
                out.add(new Field("$version", arg, null, false, null));
            }else if(IDENT.matcher(arg).matches()){
                out.add(new Field(arg, codec, null, false, null));
            }else if(arg.matches("\\w+\\.id\\|-1") && codec.equals("i16")){
                String name = arg.substring(0, arg.indexOf('.'));
                String descriptor = lift.fieldDescriptor(cls, name);
                String type = CONTENT_TYPES.get(descriptor);
                if(type == null) problems.add(where + ": " + name + " is not a known content type: " + descriptor);
                out.add(new Field(name, "ref<" + (type == null ? "?" : type) + ">", null, false, null));
            }else if(arg.matches("\\w+\\.(size|length)") && i + 1 < ops.size() && l.looped(ops.get(i + 1).index)){
                String name = arg.substring(0, arg.indexOf('.'));
                out.add(new Field(name, "seq<" + ops.get(i + 1).codec.replace("TypeIO.write", "") + ">", codec, false, null));
            }else{
                problems.add(where + ": " + codec + " " + arg);
            }
        }
        return out;
    }

    /** {@code if(REV == 0)} and {@code else if(REV == n)}: the branch tests of a generated read(). */
    private static final Pattern REVISION = Pattern.compile("REV (?:ne|mpne (\\d+))");
    /** A content field's read: {@code Vars.content.getByID(ContentType.unit, read.s())}. */
    private static final Pattern CONTENT_READ = Pattern.compile("Vars\\.content\\.getByID\\(ContentType\\.(\\w+), i16\\(\\)\\)");

    /**
     * Lifts each revision branch of a generated {@code read(Reads)} (EntityIO.java:99) into the fields it reads, by
     * revision. Old revisions read fields the class no longer has and drop them; those are named {@code _}. The
     * patterns mirror {@link #fields}: a read stored in a field, a content id through {@code getByID}, and a count read
     * into {@code <name>_LENGTH} followed by a loop over the element read.
     */
    private SortedMap<Integer, List<Field>> revisions(String cls, String where){
        SortedMap<Integer, List<Field>> out = new TreeMap<>();
        MethodModel read = lift.method(cls, "read", READ_SYNC);
        if(read == null){
            problems.add(where + ": no read(Reads) beside write()");
            return out;
        }
        Lifted l = lift.lift(cls, read);
        List<Op> ops = l.ops;
        for(Op test : ops){
            Matcher m = REVISION.matcher(test.codec);
            if(!test.kind.equals("if") || !m.matches()) continue;
            int revision = m.group(1) == null ? 0 : Integer.parseInt(m.group(1)), end = Integer.parseInt(test.arg);
            List<Op> body = ops.stream().filter(o -> o.index > test.index && o.index < end).toList();
            out.put(revision, readFields(l, body, where + " revision " + revision));
        }
        if(out.isEmpty()) problems.add(where + ": no revision branches");
        return out;
    }

    private static String compact(List<Field> fields){
        return fields == null ? "nothing" : String.join(", ", fields.stream().map(f -> f.name() + " " + f.codec() + (f.count() == null ? "" : "/" + f.count())).toList());
    }

    private List<Field> readFields(Lifted l, List<Op> body, String where){
        List<Field> out = new ArrayList<>();
        for(int i = 0; i < body.size(); i++){
            Op op = body.get(i);
            if(op.kind.equals("goto") && i == body.size() - 1) break;
            if(op.kind.equals("call")) continue;
            if(!op.kind.equals("emit") || l.looped(op.index)){
                problems.add(where + ": unexpected " + op.kind + " " + op.codec + " " + op.arg);
                continue;
            }
            String codec = op.codec.replace("TypeIO.read", "");
            Op next = i + 1 < body.size() ? body.get(i + 1) : null;
            if(next != null && next.kind.equals("let") && next.arg.equals(op.codec + "()")){
                //a count in a local, then a loop over the element. Locals share slots, so their names can't be trusted:
                //the field is the one the loop adds to (or that is cleared or replaced before it); without one it's dropped
                String name = null;
                int j = i + 2;
                for(; j < body.size() && !l.looped(body.get(j).index); j++){
                    Op p = body.get(j);
                    if(p.kind.equals("call") && p.codec.endsWith(".clear")) name = p.codec.substring(0, p.codec.indexOf('.'));
                    else if(p.kind.equals("set") && p.arg.startsWith("new ")) name = p.codec;
                    else if(!(p.kind.equals("let") && p.arg.equals("0"))) break;
                }
                if(j >= body.size() || !l.looped(body.get(j).index)){
                    problems.add(where + ": a count without a loop after it");
                    continue;
                }
                String element = null;
                for(; j < body.size() && l.looped(body.get(j).index); j++){
                    Op e = body.get(j);
                    if(e.kind.equals("emit") && element == null) element = e.codec.replace("TypeIO.read", "");
                    if(e.kind.equals("let") && CONTENT_READ.matcher(e.arg).matches()){
                        var content = CONTENT_READ.matcher(e.arg);
                        if(content.matches()) element = "ref<" + content.group(1) + ">";
                    }
                    if(e.kind.equals("call") && e.codec.endsWith(".add")) name = e.codec.substring(0, e.codec.indexOf('.'));
                }
                if(element == null) problems.add(where + ": a count and a loop without an element read");
                out.add(new Field(name == null ? "_" : name, "seq<" + element + ">", codec, false, null));
                i = j - 1;
            }else if(next != null && next.kind.equals("set") && (next.arg.equals(op.codec + "()") || CONTENT_READ.matcher(next.arg).matches())){
                Matcher content = CONTENT_READ.matcher(next.arg);
                if(codec.equals("PlansQueue")){
                    //readPlansQueue reads what write() writes for a plan queue, an i32 count of plans (TypeIO.java:639)
                    out.add(new Field(next.codec, "seq<Plan>", "i32", false, null));
                }else{
                    out.add(new Field(next.codec, content.matches() ? "ref<" + content.group(1) + ">" : codec, null, false, null));
                }
                i++;
            }else{
                //read into nothing: a field this class no longer has
                out.add(new Field("_", codec, null, false, null));
            }
        }
        return out;
    }

    /**
     * Lifts {@code @SyncLocal} (assigned under {@code if(!islocal)}) and {@code @SyncField} (assigns
     * {@code name_TARGET_}) out of readSync, and lerp or slerp out of interpolate().
     */
    private List<Field> flags(String cls, List<Field> fields){
        String read = "(L" + R + ";)V";
        MethodModel readSync = lift.resolve(cls, "readSync", read);
        if(readSync == null) return fields;
        Lifted l = lift.lift(lift.owner(cls, "readSync", read), readSync);
        List<int[]> localRanges = new ArrayList<>();
        for(Op op : l.ops){
            if(op.kind.equals("if") && op.codec.startsWith("islocal")) localRanges.add(new int[]{op.index, Integer.parseInt(op.arg)});
        }
        Set<String> local = new TreeSet<>(), interpolated = new TreeSet<>();
        for(Op op : l.ops){
            if(!op.kind.equals("set")) continue;
            String f = op.codec.replace("_TARGET_", "");
            if(op.codec.endsWith("_TARGET_")) interpolated.add(f);
            for(int[] range : localRanges) if(op.index > range[0] && op.index < range[1]) local.add(f);
        }
        Map<String, String> kind = new TreeMap<>();
        MethodModel interpolate = lift.resolve(cls, "interpolate", "()V");
        if(interpolate != null){
            for(Op op : lift.lift(lift.owner(cls, "interpolate", "()V"), interpolate).ops){
                if(op.kind.equals("set") && !op.codec.contains("_") && op.arg.contains("lerp(")) kind.put(op.codec, op.arg.contains("slerp(") ? "slerp" : "lerp");
            }
        }
        List<Field> out = new ArrayList<>();
        for(Field f : fields){
            out.add(new Field(f.name(), f.codec(), f.count(), local.contains(f.name()), interpolated.contains(f.name()) ? kind.getOrDefault(f.name(), "lerp") : null));
        }
        return out;
    }
}
