package com.xpdustry.shard.content.patch;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.schema.FieldType.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * Patchgen (ARCHITECTURE.md §5, note 20 §6): the vanilla content patch that turns a client's vanilla content into a
 * world's. It diffs the two baked epochs field by field, so derived fields (health from requirements, ranges, unit
 * dps…) are sent as values and the client needs no init code of Shard's.
 *
 * <p>Paths follow {@code DataPatcher.assign} (DataPatcher.java:420): {@code <type>.<name>.<javaField>}, list elements
 * by index, map entries by key ({@code "-"} removes one). Sub-objects of the same kind are patched field by field; a
 * changed kind or list length sends the whole value, as an object with {@code type} where the parser picks a class.
 * Bullets have no names in vanilla: a hoisted bullet ({@code duo/ammoTypes.copper}) is patched through the first path
 * that reaches it in vanilla's content. Consumers change through the block's {@code consumes}
 * ({@code ContentParser.readBlockConsumers}).
 *
 * <p>Refused, with a diagnostic each: added or removed content, a content's class, {@code @NoPatch} and hidden fields,
 * lambdas ({@code hook:}), path costs and entity classes, and new bullets or effects. New ones would get ids the
 * client makes up, and the server must never send those (note 20 §6). {@code server} fields are left out: clients
 * don't read them.
 */
public final class Patchgen{
    private static final int cacheSize = 32;
    private static final Map<String, Patch> cache = new LinkedHashMap<>(16, 0.75f, true){
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Patch> eldest){
            return size() > cacheSize;
        }
    };

    /** {@code readBlockConsumers} keys by consumer kind (ContentParser.java:523). */
    private static final Map<String, String> consumeKeys = Map.ofEntries(
        Map.entry("consume-item-flammable", "itemFlammable"), Map.entry("consume-item-radioactive", "itemRadioactive"),
        Map.entry("consume-item-explode", "itemExplode"), Map.entry("consume-item-charged", "itemCharged"),
        Map.entry("consume-item-explosive", "itemExplosive"), Map.entry("consume-item-list", "itemList"),
        Map.entry("consume-items", "items"), Map.entry("consume-liquid-flammable", "liquidFlammable"),
        Map.entry("consume-liquid", "liquid"), Map.entry("consume-liquids", "liquids"), Map.entry("consume-coolant", "coolant"),
        Map.entry("consume-power", "power"));

    private final Epoch base, world;
    private final Diagnostics diagnostics;
    private final Map<String, String> bulletPaths = new HashMap<>();
    private final Set<String> polymorphic = new HashSet<>();
    private final Map<String, Object> entries = new LinkedHashMap<>();
    private Mark mark = Mark.none;
    private int errors;

    private Patchgen(Epoch base, Epoch world, Diagnostics diagnostics){
        this.base = base;
        this.world = world;
        this.diagnostics = diagnostics;
        for(KindSchema k : base.schema().kinds()){
            if(k.parent() != null) polymorphic.add(k.parent().key());
            if(k.isAbstract()) polymorphic.add(k.key());
        }
    }

    /**
     * The patch from {@code base} (what clients have, normally {@link Epoch#vanilla()}) to {@code world}, cached by the
     * two content hashes. Throws a {@link ContentException} when the world can't be sent as a patch.
     */
    public static Patch of(Epoch base, Epoch world){
        String key = base.hash() + ":" + world.hash();
        synchronized(cache){
            Patch p = cache.get(key);
            if(p != null) return p;
        }
        var diagnostics = new Diagnostics();
        Patch p = generate(base, world, diagnostics);
        diagnostics.check();
        synchronized(cache){
            cache.put(key, p);
        }
        return p;
    }

    /** The patch from {@code base} to {@code world}; null if any part can't be patched, with the reasons in {@code diagnostics}. */
    public static Patch generate(Epoch base, Epoch world, Diagnostics diagnostics){
        if(base.schema() != world.schema()){
            diagnostics.error(Mark.none, "patches need both epochs on one schema");
            return null;
        }
        var gen = new Patchgen(base, world, diagnostics);
        gen.run();
        if(gen.errors > 0) return null;
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", "shard");
        json.putAll(gen.entries);
        String path = "shard-" + world.hash().substring(0, 16) + ".json";
        return new Patch(base.hash(), world.hash(), path, Json.write(json), List.copyOf(gen.entries.keySet()));
    }

    private void run(){
        findBulletPaths();
        for(ContentType type : ContentType.values()){
            int known = base.count(type);
            for(int id = 0; id < world.count(type); id++){
                ContentDef w = world.get(type, id);
                mark = mark(w);
                if(id >= known || !w.name().equals(base.get(type, id).name())){
                    error(type.yamlName() + " " + w.name() + " is new: patches can't add content yet");
                    continue;
                }
                Struct b = base.get(type, id).fields();
                if(b.equals(w.fields())) continue;
                String path = type == ContentType.BULLET ? bulletPaths.get(w.name()) : type.yamlName() + "." + w.name();
                if(path == null){
                    error("bullet " + w.name() + " changed, but no vanilla content reaches it, so a patch can't either");
                }else if(b.kind() != w.fields().kind()){
                    error(type.yamlName() + " " + w.name() + " changed class from " + b.kind().key() + " to " + w.fields().kind().key()
                        + ": vanilla's patcher can't change a content's class");
                }else{
                    noPatch(type, w.name());
                    fields(path, b, w.fields());
                }
            }
            if(world.count(type) < known){
                mark = Mark.none;
                error("the world lacks " + type.yamlName() + " " + base.get(type, world.count(type)).name() + ": patches can't remove content");
            }
        }
    }

    /** Authored {@code @NoPatch} changes; derived ones are left to vanilla's patcher, which derives them again. */
    private void noPatch(ContentType type, String name){
        Authored now = world.baked().loaded().get(type, name), before = base.baked().loaded().get(type, name);
        if(now == null || before == null) return;
        for(FieldSpec f : now.noPatchChanges(before)){
            error(type.yamlName() + "." + name + "." + f.javaName() + ": vanilla's patcher refuses it (@NoPatch)");
        }
    }

    private Mark mark(ContentDef def){
        var authored = world.baked().loaded().get(def.type(), def.name());
        return authored == null ? Mark.none : authored.mark();
    }

    // bullet paths

    /** Gives every bullet vanilla's content reaches its first path, walking content in type and id order. */
    private void findBulletPaths(){
        for(ContentType type : ContentType.values()){
            if(type == ContentType.BULLET) continue;
            for(int id = 0; id < base.count(type); id++){
                ContentDef def = base.get(type, id);
                walk(type.yamlName() + "." + def.name(), def.fields());
            }
        }
    }

    private void walk(String path, Struct s){
        for(FieldSpec f : s.kind().fields()){
            walk(path + "." + f.javaName(), f.type(), s.get(f));
        }
    }

    private void walk(String path, FieldType type, Object value){
        switch(value){
            case null -> {
            }
            case Struct s -> walk(path, s);
            case List<?> l -> {
                FieldType element = type instanceof ListType lt ? lt.element() : type instanceof SetType st ? st.element() : null;
                if(element == null) return;
                for(int i = 0; i < l.size(); i++) walk(path + "." + i, element, l.get(i));
            }
            case Map<?, ?> m -> {
                if(!(type instanceof MapType mt)) return;
                m.forEach((k, v) -> walk(path + "." + k, mt.value(), v));
            }
            case String name when type instanceof Ref r && r.type() == ContentType.BULLET -> {
                if(bulletPaths.putIfAbsent(name, path) == null){
                    ContentDef bullet = base.find(ContentType.BULLET, name);
                    if(bullet != null) walk(path, bullet.fields());
                }
            }
            default -> {
            }
        }
    }

    // diff

    private void fields(String path, Struct b, Struct w){
        KindSchema kind = w.kind();
        boolean consumers = false;
        for(FieldSpec f : kind.slots()){
            Object bv = b.get(f), wv = w.get(f);
            if(Objects.equals(bv, wv)) continue;
            String at = path + "." + f.javaName();
            if(kind.field(f.name()) != f){
                error(at + ": the field " + f.owner().key() + "." + f.name() + " is hidden by a subclass field, which is the only one a patch reaches");
            }else if(f.tag() == Tag.SERVER){
                // clients never read it
            }else if(f.name().equals("consumes") && f.owner().key().equals("block")){
                consumers = true;
            }else if(!f.noPatch()){
                value(at, f.type(), bv, wv);
            }
            // other @NoPatch fields (hasItems, itemFilter…) are derived; vanilla's patcher derives them again
        }
        // last: rebuilding consumers re-adds a turret's coolant field, which must be patched by then
        if(consumers) consumers(path, b, w);
    }

    private void value(String at, FieldType type, Object bv, Object wv){
        if(bv instanceof Struct bs && wv instanceof Struct ws && bs.kind() == ws.kind()){
            fields(at, bs, ws);
        }else if(type instanceof ListType lt && bv instanceof List<?> bl && wv instanceof List<?> wl && bl.size() == wl.size()){
            for(int i = 0; i < bl.size(); i++){
                if(!Objects.equals(bl.get(i), wl.get(i))) value(at + "." + i, lt.element(), bl.get(i), wl.get(i));
            }
        }else if(type instanceof MapType mt && bv instanceof Map<?, ?> bm && wv instanceof Map<?, ?> wm){
            wm.forEach((k, v) -> {
                if(!bm.containsKey(k)) set(at + "." + k, mt.value(), v);
                else if(!Objects.equals(bm.get(k), v)) value(at + "." + k, mt.value(), bm.get(k), v);
            });
            for(Object k : bm.keySet()){
                if(!wm.containsKey(k)) entries.put(at + "." + k, "-");
            }
        }else if(type == FieldType.ATTRIBUTES && bv instanceof Map<?, ?> bm && wv instanceof Map<?, ?> wm){
            // Attributes are patched per attribute (DataPatcher.java:603); an absent one is 0
            Set<Object> keys = new LinkedHashSet<>(bm.keySet());
            keys.addAll(wm.keySet());
            for(Object k : keys){
                Object v = wm.containsKey(k) ? wm.get(k) : 0f;
                if(!Objects.equals(bm.containsKey(k) ? bm.get(k) : 0f, v)) entries.put(at + "." + k, v);
            }
        }else{
            set(at, type, wv);
        }
    }

    /**
     * A block's consumers. {@code consumeBuilder} and the arrays built from it are {@code @NoPatch} (Block.java:427),
     * so a patch edits them through {@code consumes} (ContentParser.java:523): {@code remove} drops every consumer of
     * a class and its subclasses, the other keys append one consumer each. The patch removes the classes of the
     * consumers the world drops or changes and appends what is then missing; consumers vanilla's {@code init()} adds
     * with lambdas (an item turret's ammo filter) stay. Consumer order is not kept: nothing but the stats page reads it.
     */
    private void consumers(String path, Struct before, Struct after){
        List<Struct> b = new ArrayList<>(before.structs("consumes")), w = new ArrayList<>(after.structs("consumes"));
        if(after.kind().isA("base-turret")){
            // BaseTurret.checkInitCoolant (BaseTurret.java:70) adds the coolant field's object to the consumers, and
            // the field patches that same object in place; only a coolant of another class leaves an old one to remove
            Struct bc = before.struct("coolant"), wc = after.struct("coolant");
            if(wc != null) w.remove(wc);
            if(bc != null && wc != null && bc.kind() == wc.kind()) b.remove(bc);
        }
        List<Struct> dropped = new ArrayList<>(b);
        for(Struct c : w) dropped.remove(c);
        Set<String> remove = new LinkedHashSet<>();
        for(Struct c : dropped) remove.add(removeKey(c.kind()));
        List<Struct> kept = new ArrayList<>();
        for(Struct c : b){
            if(!removedBy(c.kind(), remove)) kept.add(c);
        }
        List<Struct> added = new ArrayList<>(w);
        for(Struct c : kept) added.remove(c);
        if(remove.isEmpty() && added.isEmpty()) return;

        Map<String, Object> out = new LinkedHashMap<>();
        if(!remove.isEmpty()) out.put("remove", List.copyOf(remove));
        for(Struct c : added){
            String key = consumeKeys.get(c.kind().key());
            if(key == null){
                error(path + ".consumes: a " + c.kind().key() + " can't be written as a consumer patch");
                return;
            }
            if(out.containsKey(key)){
                error(path + ".consumes: two " + c.kind().key() + " consumers; a consumer patch adds one of each");
                return;
            }
            try{
                out.put(key, object(c, false));
            }catch(Refused r){
                error(path + ".consumes." + key + ": " + r.getMessage());
                return;
            }
        }
        entries.put(path + ".consumes", out);
    }

    /** The {@code remove} name of a consumer class: {@code ConsumeItemFilter} is {@code itemFilter}. */
    private static String removeKey(KindSchema kind){
        String name = kind.vanillaName().substring("Consume".length());
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Whether removing {@code keys} drops a consumer of {@code kind}: removal matches subclasses too. */
    private static boolean removedBy(KindSchema kind, Set<String> keys){
        for(KindSchema k = kind; k != null; k = k.parent()){
            if(k.vanillaName().startsWith("Consume") && !k.vanillaName().equals("Consume") && keys.contains(removeKey(k))) return true;
        }
        return false;
    }

    private void set(String at, FieldType type, Object value){
        try{
            entries.put(at, encode(type, value));
        }catch(Refused r){
            error(at + ": " + r.getMessage());
        }
    }

    private void error(String message){
        errors++;
        diagnostics.error(mark, "can't patch " + message);
    }

    // values as vanilla's ContentParser reads them

    private static final class Refused extends Exception{
        Refused(String message){
            super(message, null, false, false);
        }
    }

    private Object encode(FieldType type, Object v) throws Refused{
        if(v == null) return null;
        return switch(type){
            case Scalar s -> switch(s){
                case COLOR -> ((String)v).substring(1);
                case FLOAT -> finite((Float)v);
                case DOUBLE -> {
                    if(!Double.isFinite((Double)v)) throw new Refused("JSON has no " + v);
                    yield v;
                }
                default -> v;
            };
            case EnumType e -> v;
            case Ref r -> ref(r, (String)v);
            case Named n -> named(n, v);
            case Special s -> special(s, v);
            case ListType l -> list(l.element(), (List<?>)v);
            case SetType s -> list(s.element(), (List<?>)v);
            case MapType m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for(var e : ((Map<?, ?>)v).entrySet()) out.put((String)e.getKey(), encode(m.value(), e.getValue()));
                yield out;
            }
            case Obj o -> object((Struct)v, polymorphic.contains(o.base()));
        };
    }

    private static Float finite(float f) throws Refused{
        if(!Float.isFinite(f)) throw new Refused("JSON has no " + f);
        return f;
    }

    private List<Object> list(FieldType element, List<?> values) throws Refused{
        List<Object> out = new ArrayList<>(values.size());
        for(Object o : values) out.add(encode(element, o));
        return out;
    }

    private Object ref(Ref ref, String name) throws Refused{
        if(ref.type() == ContentType.BULLET){
            // only Bullets.* statics have names a client can look up (ContentParser.java:179)
            if(name.indexOf('/') < 0 && base.find(ContentType.BULLET, name) != null) return camel(name);
            throw new Refused("it would point at bullet " + name + ", and a patch can only name Bullets.* statics; a copy would be "
                + "a new bullet with a client-made id");
        }
        if(ref.name().equals("content")) return name.substring(name.indexOf(':') + 1);
        return name;
    }

    private Object named(Named type, Object v) throws Refused{
        if(v instanceof Struct s){
            if(type.registry().equals("sound") && s.kind().key().equals("random-sound")) return list(new Named("sound"), s.list("sounds"));
            throw new Refused("a new " + type.registry() + " object (" + s.kind().key() + ") would get a client-made id");
        }
        String text = (String)v;
        if(text.startsWith("hook:")) throw new Refused("it holds a lambda (" + text + "), which JSON can't carry");
        if(type.registry().equals("cost")) throw new Refused("vanilla's parser can't read path costs");
        return text.substring(text.indexOf(':') + 1);
    }

    private Object special(Special type, Object v) throws Refused{
        return switch(type.name()){
            case "entity" -> throw new Refused("a unit's entity class decides its sync layout");
            case "ai" -> throw new Refused("controllers are lambdas");
            case "hook" -> {
                if(v instanceof Struct s) yield object(s, true);
                throw new Refused("it holds a lambda (" + v + "), which JSON can't carry");
            }
            case "stack", "liquid-stack", "payload-stack" -> {
                var e = ((Map<?, ?>)v).entrySet().iterator().next();
                yield stack((String)e.getKey(), e.getValue());
            }
            case "stacks", "liquid-stacks" -> {
                List<Object> out = new ArrayList<>();
                for(var e : ((Map<?, ?>)v).entrySet()) out.add(stack((String)e.getKey(), e.getValue()));
                yield out;
            }
            default -> v; // attribute, attributes, team
        };
    }

    /** {@code "copper/35"}, the stack syntax ContentParser reads (ContentParser.java:466). */
    private static String stack(String name, Object amount) throws Refused{
        if(amount instanceof Float f) finite(f);
        return name + "/" + amount;
    }

    /** A whole object: the fields that differ from a fresh instance, and its class where the parser picks one. */
    private Map<String, Object> object(Struct s, boolean withType) throws Refused{
        Map<String, Object> out = new LinkedHashMap<>();
        if(withType) out.put("type", s.kind().vanillaName());
        Struct fresh = s.kind().defaultStruct();
        for(FieldSpec f : s.kind().fields()){
            Object v = s.get(f);
            if(Objects.equals(v, fresh.get(f)) || f.tag() == Tag.SERVER) continue;
            if(f.noPatch()) throw new Refused("its field " + f.name() + " can't be patched");
            out.put(f.javaName(), encode(f.type(), v));
        }
        return out;
    }

    /** {@code damage-lightning} → {@code damageLightning}, the {@code Bullets} field. */
    private static String camel(String kebab){
        var out = new StringBuilder();
        boolean up = false;
        for(char c : kebab.toCharArray()){
            if(c == '-'){
                up = true;
            }else{
                out.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return out.toString();
    }
}
