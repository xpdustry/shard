package com.xpdustry.shard.tools.extract;

import mindustry.ctype.*;
import com.xpdustry.shard.content.bake.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;

import java.util.*;
import java.util.regex.*;

/**
 * Vanilla's baked content next to Shard's: every field of every content object, as plain trees. Vanilla boots
 * through the oracle and is encoded in full mode (every field, sub-objects whole); Shard loads and bakes the committed
 * pack. Positional hook names ({@code Turret#1}) depend on the order a run meets lambdas, so they compare without
 * their number.
 */
final class Fidelity{
    final Extract extract = new Extract();
    /** What the extractor writes now: pack path → text. */
    final SortedMap<String, String> files;
    final Loaded loaded;
    final Baked baked;
    final Encoder full;
    /** The bake's errors; the gate wants none. */
    final List<com.xpdustry.shard.content.yaml.Diagnostic> diagnostics;

    Fidelity(){
        files = extract.run();
        // run() leaves the authored snapshot in Vars.content; patches must reach the booted content
        mindustry.Vars.content = extract.booted;
        loaded = Loader.load(Pack.vanilla());
        var diagnostics = new com.xpdustry.shard.content.yaml.Diagnostics();
        baked = Bake.bake(loaded, diagnostics);
        this.diagnostics = diagnostics.list();
        List<String> bullets = loaded.names(com.xpdustry.shard.content.ContentType.BULLET);
        full = new Encoder(extract.kinds, b -> bullets.get(b.id));
    }

    static mindustry.ctype.ContentType vanillaType(com.xpdustry.shard.content.ContentType type){
        return mindustry.ctype.ContentType.valueOf(type.yamlName());
    }

    /** Vanilla's baked {@code type} content by id. */
    Map<String, Object> vanilla(com.xpdustry.shard.content.ContentType type, int id){
        Content c = extract.booted.getByID(vanillaType(type), id);
        String name = c instanceof MappableContent m ? m.name : String.valueOf(id);
        @SuppressWarnings("unchecked") var out = (Map<String, Object>)normalize(full.content(c, name + "/", null));
        return out;
    }

    int vanillaCount(com.xpdustry.shard.content.ContentType type){
        return extract.booted.getBy(vanillaType(type)).size;
    }

    Map<String, Object> shard(com.xpdustry.shard.content.ContentType type, int id){
        return plain(baked.of(type).get(id));
    }

    /** A Shard struct as a plain tree, normalized like vanilla's. */
    static Map<String, Object> plain(Struct s){
        @SuppressWarnings("unchecked") var out = s == null ? null : (Map<String, Object>)normalize(s.toPlain());
        return out;
    }

    private static final Pattern positional = Pattern.compile("(hook:.*)#\\d+");

    /** Strips positional hook numbers. */
    static Object normalize(Object value){
        return switch(value){
            case String s -> positional.matcher(s).matches() ? positional.matcher(s).replaceAll("$1") : s;
            case List<?> l -> {
                List<Object> out = new ArrayList<>(l.size());
                for(Object o : l) out.add(normalize(o));
                yield out;
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put((String)k, normalize(v)));
                yield out;
            }
            case null, default -> value;
        };
    }

    /** Every place where {@code a} (vanilla) and {@code b} (Shard) differ, as {@code path: vanilla ≠ shard}. */
    static void diff(String path, Object a, Object b, List<String> out){
        if(Objects.equals(a, b)) return;
        if(a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb && Objects.equals(ma.get("kind"), mb.get("kind"))){
            Set<Object> keys = new LinkedHashSet<>(ma.keySet());
            keys.addAll(mb.keySet());
            for(Object k : keys){
                if(!ma.containsKey(k)) out.add(path + "." + k + ": only in shard = " + brief(mb.get(k)));
                else if(!mb.containsKey(k)) out.add(path + "." + k + ": only in vanilla = " + brief(ma.get(k)));
                else diff(path + "." + k, ma.get(k), mb.get(k), out);
            }
            return;
        }
        if(a instanceof List<?> la && b instanceof List<?> lb && la.size() == lb.size()){
            for(int i = 0; i < la.size(); i++) diff(path + "." + i, la.get(i), lb.get(i), out);
            return;
        }
        out.add(path + ": vanilla " + brief(a) + " ≠ shard " + brief(b));
    }

    static String brief(Object o){
        String s = String.valueOf(o);
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }
}
