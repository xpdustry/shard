package com.xpdustry.shard.tools.extract;

import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.entities.bullet.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import com.xpdustry.shard.oracle.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

import com.xpdustry.shard.tools.extract.FieldType.*;
import com.xpdustry.shard.tools.extract.Kinds.*;

/**
 * Generates the vanilla content pack ({@code ./gradlew :tools:content-extract:extract}): YAML prototypes that hold only
 * what differs from a fresh instance of their class (note 20 §6, proto 20 {@code Extract.java}), the schema with class
 * defaults, and the wire id order. Argument: the output directory, normally
 * {@code content/src/main/resources/content/vanilla}. Output is deterministic.
 *
 * <p>Vanilla boots through the oracle; its content is then created a second time in a fresh {@link ContentLoader} and
 * not initialized, which is the authored snapshot. {@code init()}-derived values are left out: Shard bakes them.
 */
public final class Extract{
    /** Content types in the pack, in wire order (ContentType ordinals). */
    static final List<ContentType> types = List.of(ContentType.item, ContentType.block, ContentType.bullet, ContentType.liquid,
        ContentType.status, ContentType.unit, ContentType.weather);

    final Names names;
    final Kinds kinds;
    ContentLoader booted, authored;
    final IdentityHashMap<BulletType, String> bulletIds = new IdentityHashMap<>();
    final IdentityHashMap<Object, String> effectNames = new IdentityHashMap<>();
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    /** {@code Effect.all.size} right after boot: the effects every client has, before the authored load adds its own. */
    private int effectCount;

    /** Boots vanilla first: some statics ({@code CacheLayer.normal}) only exist after {@code Vars.init()}. */
    public Extract(){
        Vanilla.boot();
        names = new Names();
        kinds = new Kinds(names);
    }

    public static void main(String[] args) throws IOException{
        if(args.length != 1) throw new IllegalArgumentException("usage: Extract <output dir>");
        Path out = Path.of(args[0]).toAbsolutePath();
        var extract = new Extract();
        SortedMap<String, String> files = extract.run();
        write(out, files);
        copyBaseParts(out);
        long bytes = files.values().stream().mapToLong(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        long lines = files.values().stream().mapToLong(s -> s.lines().count()).sum();
        System.out.printf("%d files, %d lines, %.1f KiB in %s%n", files.size(), lines, bytes / 1024.0, out);
        if(!extract.kinds.skipped.isEmpty()){
            System.out.println("fields without a YAML form (left out of the schema):");
            extract.kinds.skipped.forEach(s -> System.out.println("  " + s));
        }
    }

    /**
     * Copies vanilla's base-part schematics ({@code basepartnames} and {@code baseparts/}, which {@code BaseRegistry}
     * loads) into the pack as they are, for {@code content.base.BaseParts} (WP82).
     */
    static void copyBaseParts(Path out) throws IOException{
        byte[] names = arc.Core.files.internal("basepartnames").readBytes();
        Files.write(out.resolve("basepartnames"), names);
        Path dir = out.resolve("baseparts");
        Files.createDirectories(dir);
        for(String name : new String(names, java.nio.charset.StandardCharsets.UTF_8).split("\n")){
            Files.write(dir.resolve(name), arc.Core.files.internal("baseparts/" + name).readBytes());
        }
    }

    /** Replaces the {@code .yml} files under {@code out} with {@code files}. */
    static void write(Path out, SortedMap<String, String> files) throws IOException{
        if(Files.isDirectory(out)){
            try(var walk = Files.walk(out)){
                for(Path p : walk.filter(p -> p.toString().endsWith(".yml")).toList()) Files.delete(p);
            }
        }
        for(var e : files.entrySet()){
            Path file = out.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }
    }

    /** The pack: path relative to the pack directory → file text. */
    public SortedMap<String, String> run(){
        booted = Vars.content;
        effectCount = Effect.all.size;
        Map<Object, String> bootedBullets = staticBullets();
        probes(names, kinds, booted);
        nameEffects();

        authored = new ContentLoader();
        Vars.content = authored;
        // ErekirTechTree.rebalance scales bullet damage once per bullet id; the boot already marked every id
        erekirBalanced().clear();
        authored.createBaseContent();
        Map<Object, String> authoredBullets = staticBullets();
        authoredBullets.forEach((b, name) -> bulletIds.put((BulletType)b, name));

        discover();

        var effective = new HashMap<Kind, Map<String, Object>>();
        Deque<Map.Entry<BulletType, String>> hoisted = new ArrayDeque<>();
        var encoder = new Encoder(kinds, Encoder.Mode.pack, bulletIds, (b, id) -> hoisted.add(Map.entry(b, id)), effective);

        Map<String, List<Object>> files = new TreeMap<>();
        for(ContentType type : types){
            if(type == ContentType.bullet) continue;
            for(Content c : authored.getBy(type)){
                var mappable = (MappableContent)c;
                Content twin = booted.getByID(type, c.id);
                Map<String, Object> doc = document(type, mappable.name);
                doc.putAll(encoder.content(c, mappable.name + "/", prop -> prop.get(prop.extracted ? twin : c)));
                List<Object> file = files.computeIfAbsent(file(c), k -> new ArrayList<>());
                file.add(doc);
                drain(encoder, hoisted, file);
            }
        }
        List<Object> bulletFile = files.computeIfAbsent("bullets.yml", k -> new ArrayList<>());
        for(var e : authoredBullets.entrySet()){
            var bullet = (BulletType)e.getKey();
            Map<String, Object> doc = document(ContentType.bullet, e.getValue());
            doc.putAll(encoder.content(bullet, e.getValue() + "/", null));
            bulletFile.add(doc);
            drain(encoder, hoisted, bulletFile);
        }
        for(BulletType b : authored.bullets()){
            if(bulletIds.containsKey(b)) continue;
            String id = "bullet-" + b.id;
            bulletIds.put(b, id);
            Map<String, Object> doc = document(ContentType.bullet, id);
            doc.putAll(encoder.content(b, id + "/", null));
            bulletFile.add(doc);
            drain(encoder, hoisted, bulletFile);
        }

        SortedMap<String, String> out = new TreeMap<>();
        files.forEach((name, docs) -> out.put(name, header("Prototypes") + Yaml.documents(docs)));
        out.put("ids.yml", header("Wire ids: names in id order per content type (ContentType ordinal order)") + Yaml.mapping(ids(bootedBullets)));
        out.put("kinds.yml", header("The schema: kinds, their fields (type, tag, class default) and class defaults") + Yaml.mapping(schema(encoder)));
        out.put("client.yml", header("What the client numbers or shows that the pack does not describe (ClientTables)") + Yaml.mapping(clientTables()));
        out.put("pack.yml", header("The vanilla pack: files in load order") + Yaml.mapping(manifest(out.keySet())));
        return out;
    }

    /**
     * Gives the lambdas that the simulation implements their names ({@code hook:<name>} in YAML), by probing the
     * objects that hold them: the unit controller defaults, the status reaction that {@code opposite} installs, and
     * the lambdas blocks put on consumers, named {@code <block-kind>.<field>} after the block class that wrote them.
     * Other lambdas keep positional names ({@code UnitTypes$46$1#0}). Tests run the same probes.
     */
    static void probes(Names names, Kinds kinds, ContentLoader booted){
        var unit = (UnitType)kinds.fresh(kinds.kind(UnitType.class));
        names.hook(unit.aiController, "default-ai");
        names.hook(unit.controller, "default-controller");
        // the core units' controller, u -> u.team.isAI() ? new BuilderAI(true, 400f) : new CommandAI(), is one lambda
        // per UnitTypes initializer; a stable name keeps content from depending on anonymous class numbering
        for(UnitType u : List.of(UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma)){
            if(!Names.isLambda(u.controller) || u.controller.getClass().getNestHost() != UnitTypes.class){
                throw new IllegalStateException(u.name + "'s controller is not UnitTypes' core-unit lambda: " + u.controller);
            }
            names.sharedHook(u.controller, "core-unit-controller");
        }

        Prop transitions = kinds.kind(StatusEffect.class).prop("transitions");
        kinds.inScratch(() -> {
            var a = new StatusEffect("shard-probe-a");
            var b = new StatusEffect("shard-probe-b");
            a.opposite(b);
            @SuppressWarnings("unchecked") var map = (arc.struct.ObjectMap<Object, Object>)transitions.get(a);
            names.hook(map.get(b), "status-opposite");
            return null;
        });

        // UnitType.init gives tread units an effect of their own; its renderer is one lambda
        Prop renderer = kinds.kind(Effect.class).prop("renderer");
        for(UnitType u : booted.units()){
            if(u.treadEffect == null) continue;
            Object lambda = renderer.get(u.treadEffect);
            if(Names.isLambda(lambda) && lambda.getClass().getNestHost() == UnitType.class) names.hook(lambda, "unit-type.tread-effect");
        }

        Prop consumes = kinds.kind(Block.class).prop("consumes");
        for(Block block : booted.blocks()){
            for(Object consume : (arc.struct.Seq<?>)consumes.get(block)){
                for(Prop prop : kinds.kind(consume.getClass()).all){
                    if(prop.type != FieldType.HOOK) continue;
                    Object lambda = prop.get(consume);
                    if(lambda == null || !Names.isLambda(lambda)) continue;
                    Class<?> host = lambda.getClass().getNestHost();
                    if(!Block.class.isAssignableFrom(host)) continue;
                    names.hook(lambda, Names.kind(host) + "." + prop.name);
                }
            }
        }
    }

    private static Map<String, Object> document(ContentType type, String id){
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", typeName(type));
        doc.put("id", id);
        return doc;
    }

    /** Hoisted bullets become prototypes right after their owner. */
    private void drain(Encoder encoder, Deque<Map.Entry<BulletType, String>> hoisted, List<Object> file){
        while(!hoisted.isEmpty()){
            var e = hoisted.poll();
            Map<String, Object> doc = document(ContentType.bullet, e.getValue());
            doc.putAll(encoder.content(e.getKey(), e.getValue(), null));
            file.add(doc);
        }
    }

    static String typeName(ContentType type){
        return switch(type){
            case unitCommand -> "unit-command";
            case unitStance -> "unit-stance";
            default -> type.name();
        };
    }

    /** {@code blocks/<package>.yml} by the block's package; one file per other content type. */
    private static String file(Content c){
        if(c instanceof Block b){
            String pkg = Names.concrete(b.getClass()).getPackageName();
            String prefix = "mindustry.world.blocks.";
            String group = pkg.startsWith(prefix) ? pkg.substring(prefix.length()) : "special";
            group = group.equals("defense.turrets") ? "turrets" : group.replace('.', '-');
            return "blocks/" + group + ".yml";
        }
        return switch(c.getContentType()){
            case item -> "items.yml";
            case liquid -> "liquids.yml";
            case status -> "status.yml";
            case unit -> "units.yml";
            case weather -> "weathers.yml";
            default -> throw new IllegalArgumentException(c.toString());
        };
    }

    private static String header(String what){
        return "# " + what + ". Generated by :tools:content-extract from Mindustry v" + Vanilla.build + "." + Vanilla.revision
            + "; do not edit.\n";
    }

    private static arc.struct.IntSet erekirBalanced(){
        try{
            Field f = ErekirTechTree.class.getDeclaredField("balanced");
            f.setAccessible(true);
            return (arc.struct.IntSet)f.get(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /** {@code Bullets.*} statics: the only bullets vanilla names. */
    private static Map<Object, String> staticBullets(){
        Map<Object, String> out = new LinkedHashMap<>();
        for(Field f : Bullets.class.getFields()){
            if(!Modifier.isStatic(f.getModifiers()) || !BulletType.class.isAssignableFrom(f.getType())) continue;
            try{
                Object b = f.get(null);
                if(b != null) out.putIfAbsent(b, Names.staticId(f.getName()));
            }catch(IllegalAccessException e){
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    // discovery: every class content reaches, and the classes their fresh instances reach

    private void discover(){
        for(ContentLoader loader : List.of(authored, booted)){
            for(ContentType type : types){
                for(Content c : loader.getBy(type)) walk(c);
            }
        }
        int known;
        do{
            known = kinds.byClass.size();
            for(Kind kind : List.copyOf(kinds.byClass.values())){
                Object fresh = kinds.fresh(kind);
                if(fresh != null) walk(fresh);
            }
        }while(kinds.byClass.size() != known);
    }

    private void walk(Object object){
        if(!visited.add(object)) return;
        Kind kind = kinds.kind(object.getClass());
        for(Prop prop : kind.all) walk(prop.get(object), prop.type);
    }

    private void walk(Object v, FieldType type){
        if(v == null) return;
        switch(type){
            case Obj o -> walk(v);
            case Named n -> {
                if(names.named(v) == null && !Names.isLambda(v) && !(v instanceof arc.audio.Sound && !(v instanceof arc.audio.RandomSound))) walk(v);
            }
            case Special s -> {
                if(s.name().equals("hook") && !Names.isLambda(v)) walk(v);
            }
            case ListType l -> Encoder.each(v, e -> walk(e, l.element()));
            case SetType s -> Encoder.each(v, e -> walk(e, s.element()));
            case MapType m -> {
                if(v instanceof arc.struct.ObjectMap<?, ?> map){
                    for(Object value : map.values().toSeq()) walk(value, m.value());
                }
            }
            default -> {}
        }
    }

    // effects: Fx statics by name, inline effects by the path where the booted content holds them

    private void nameEffects(){
        Deque<Map.Entry<Object, String>> queue = new ArrayDeque<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for(ContentType type : types){
            if(type == ContentType.bullet) continue;
            for(Content c : booted.getBy(type)) queue.add(Map.entry(c, ((MappableContent)c).name + "/"));
        }
        for(BulletType b : booted.bullets()) queue.add(Map.entry(b, "bullet-" + b.id + "/"));
        while(!queue.isEmpty()){
            var e = queue.poll();
            Object object = e.getKey();
            if(!seen.add(object)) continue;
            if(object instanceof Effect && names.named(object) == null) effectNames.putIfAbsent(object, e.getValue());
            Kind kind = kinds.kind(object.getClass());
            for(Prop prop : kind.all){
                if(prop.type instanceof Ref) continue;
                String path = e.getValue().endsWith("/") ? e.getValue() + prop.name : e.getValue() + "." + prop.name;
                children(prop.get(object), prop.type, path, queue);
            }
        }
    }

    private void children(Object v, FieldType type, String path, Deque<Map.Entry<Object, String>> queue){
        if(v == null || Names.isLambda(v) || names.named(v) != null) return;
        switch(type){
            case Obj o -> queue.add(Map.entry(v, path));
            case Named n -> {
                if(v instanceof Effect || v instanceof arc.math.Interp && !(v instanceof arc.audio.Sound)) queue.add(Map.entry(v, path));
            }
            case ListType l -> {
                int[] i = {0};
                Encoder.each(v, e -> children(e, l.element(), path + "." + i[0]++, queue));
            }
            default -> {}
        }
    }

    // ids, schema, manifest

    private Map<String, Object> ids(Map<Object, String> bootedBullets){
        Map<String, Object> out = new LinkedHashMap<>();
        for(ContentType type : List.of(ContentType.item, ContentType.block, ContentType.bullet, ContentType.liquid,
            ContentType.status, ContentType.unit, ContentType.weather, ContentType.unitCommand, ContentType.unitStance)){
            List<Object> list = new ArrayList<>();
            if(type == ContentType.bullet){
                if(booted.bullets().size != authored.bullets().size){
                    throw new IllegalStateException("init() created bullets: " + authored.bullets().size + " → " + booted.bullets().size);
                }
                for(int i = 0; i < booted.bullets().size; i++){
                    String id = bulletIds.get(authored.bullets().get(i));
                    String bootedName = bootedBullets.get(booted.bullets().get(i));
                    if(bootedName != null && !bootedName.equals(id)) throw new IllegalStateException("bullet " + i + ": " + id + " vs " + bootedName);
                    list.add(id);
                }
            }else{
                for(Content c : booted.getBy(type)) list.add(((MappableContent)c).name);
            }
            out.put(typeName(type), list);
        }
        List<Object> effects = new ArrayList<>();
        for(int i = 0; i < effectCount; i++){
            Effect e = Effect.all.get(i);
            String name = names.named(e);
            effects.add(name != null ? name : effectNames.getOrDefault(e, "effect#" + i));
        }
        out.put("effect", effects);
        return out;
    }

    /**
     * The booted client's tables, which every epoch shares: the sector and planet names its content header lists, in id
     * order; the content of each type by logic id, as {@code GlobalVars} reads {@code logicids.dat}; and the char of each
     * icon {@code Fonts.loadContentIconsHeadless} reads, by content name.
     */
    private Map<String, Object> clientTables(){
        Map<String, Object> out = new LinkedHashMap<>();
        for(ContentType type : List.of(ContentType.sector, ContentType.planet)){
            List<Object> names = new ArrayList<>();
            for(Content c : booted.getBy(type)) names.add(((MappableContent)c).name);
            out.put(typeName(type), names);
        }
        Map<String, Object> logic = new LinkedHashMap<>();
        Object[][] byLogicId = (Object[][])privateField(mindustry.logic.GlobalVars.class, "logicIdToContent", Vars.logicVars);
        for(ContentType type : mindustry.logic.GlobalVars.writableLookableContent){
            List<Object> names = new ArrayList<>();
            for(Object c : byLogicId[type.ordinal()]){
                if(c == null) throw new IllegalStateException("logicids.dat names " + type + " content that does not exist");
                names.add(((MappableContent)c).name);
            }
            logic.put(typeName(type), names);
        }
        out.put("logic-ids", logic);
        var icons = (arc.struct.ObjectIntMap<?>)privateField(mindustry.ui.Fonts.class, "unicodeIcons", null);
        Map<String, Object> emoji = new TreeMap<>();
        for(var e : icons) emoji.put((String)e.key, e.value);
        out.put("emoji", emoji);
        return out;
    }

    private static Object privateField(Class<?> type, String field, Object owner){
        try{
            Field f = type.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(owner);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(type.getSimpleName() + "." + field, e);
        }
    }

    private Map<String, Object> schema(Encoder encoder){
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> registries = new LinkedHashMap<>();
        names.registryNames.forEach((prefix, list) -> registries.put(prefix, new ArrayList<Object>(list)));
        out.put("registries", registries);

        Map<String, Object> enums = new TreeMap<>();
        Map<String, Object> entities = new TreeMap<>();
        List<Kind> sorted = new ArrayList<>(kinds.byClass.values());
        sorted.sort(Comparator.comparing(k -> k.key));
        Map<String, Object> kindMap = new LinkedHashMap<>();
        for(Kind kind : sorted){
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("class", kind.type.getName());
            if(kind.parent != null) k.put("parent", kind.parent.key);
            if(kind.isAbstract) k.put("abstract", true);
            ContentType root = Kinds.root(kind.type);
            if(root != null && types.contains(root)) k.put("type", typeName(root));
            if(Block.class.isAssignableFrom(kind.type)) k.put("build", Kinds.buildClass(kind.type).getName().replace("mindustry.", ""));
            Map<String, Object> fields = new LinkedHashMap<>();
            Map<String, Object> declared = Defaults.declared(encoder, kind);
            for(Prop prop : kind.fields){
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("type", prop.type.name());
                f.put("tag", prop.tag.name());
                if(prop.noPatch) f.put("noPatch", true);
                if(prop.extracted) f.put("baked", "extracted");
                if(!prop.name.equals(prop.field.getName())) f.put("java", prop.field.getName());
                f.put("default", declared.get(prop.name));
                fields.put(prop.name, f);
                collectEnums(prop.type, enums);
            }
            if(!fields.isEmpty()) k.put("fields", fields);
            Map<String, Object> overrides = Defaults.overrides(encoder, kind);
            if(!overrides.isEmpty()) k.put("defaults", overrides);
            kindMap.put(kind.key, k);
        }
        for(UnitType unit : booted.units()){
            Object entity = unit.constructor.get();
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", ((Entityc)entity).classId());
            List<Object> traitList = new ArrayList<>();
            Kinds.traits.forEach((type, trait) -> {
                if(type.isInstance(entity)) traitList.add(trait);
            });
            e.put("traits", traitList);
            entities.put(entity.getClass().getSimpleName(), e);
        }
        out.put("enums", enums);
        out.put("entities", entities);
        out.put("kinds", kindMap);
        return out;
    }

    private static void collectEnums(FieldType type, Map<String, Object> enums){
        switch(type){
            case EnumType e -> {
                List<Object> constants = new ArrayList<>();
                for(Object c : e.type().getEnumConstants()) constants.add(((Enum<?>)c).name());
                enums.put(e.type().getSimpleName(), constants);
            }
            case ListType l -> collectEnums(l.element(), enums);
            case SetType s -> collectEnums(s.element(), enums);
            case MapType m -> {
                collectEnums(m.key(), enums);
                collectEnums(m.value(), enums);
            }
            default -> {}
        }
    }

    private Map<String, Object> manifest(Set<String> files){
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vanilla", Vanilla.build + "." + Vanilla.revision);
        out.put("schema", "kinds.yml");
        out.put("ids", "ids.yml");
        out.put("client", "client.yml");
        List<Object> order = new ArrayList<>();
        for(String f : List.of("items.yml", "liquids.yml", "status.yml", "weathers.yml", "bullets.yml", "units.yml")){
            if(files.contains(f)) order.add(f);
        }
        for(String f : files){
            if(f.startsWith("blocks/")) order.add(f);
        }
        out.put("files", order);
        return out;
    }
}
