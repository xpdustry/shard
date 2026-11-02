package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.schema.FieldType.*;

import java.util.*;

/** Small helpers the inits share. */
final class Values{
    /** Vanilla's {@code Vars.tilesize}. */
    static final float tilesize = 8f;

    private Values(){
    }

    /** Whether a sound field still holds {@code Sounds.unset}, the marker inits replace by size. */
    static boolean unset(Mut m, String field){
        return "sound:unset".equals(m.get(field));
    }

    /** {@code new RandomSound(Sounds.a, Sounds.b)}. */
    static Mut randomSound(Bake bake, String a, String b){
        return bake.create("random-sound").set("sounds", new ArrayList<>(List.of("sound:" + a, "sound:" + b)));
    }

    /** Adds {@code value} to a set field, keeping the canonical order (enum ordinal, content id, text). */
    static void addToSet(Bake bake, Mut m, String field, String value){
        List<Object> set = m.list(field);
        if(set.contains(value)) return;
        set.add(value);
        set.sort(order(bake, ((SetType)m.kind.field(field).type()).element()));
    }

    static Comparator<Object> order(Bake bake, FieldType element){
        if(element instanceof EnumType e && bake.schema.enumConstants(e.enumName()) != null){
            List<String> constants = bake.schema.enumConstants(e.enumName());
            return Comparator.comparingInt(o -> constants.indexOf((String)o));
        }
        if(element instanceof Ref r && r.type() != null) return Comparator.comparingInt(o -> bake.id(r.type(), (String)o));
        return Comparator.comparing(String::valueOf);
    }

    /** Item stacks in item id order, as {@code Block.requirements(…)} sorts them. */
    static Map<String, Object> sortedStacks(Bake bake, Map<String, Object> stacks){
        List<Map.Entry<String, Object>> entries = new ArrayList<>(stacks.entrySet());
        entries.sort(Comparator.comparingInt(e -> bake.id(ContentType.ITEM, e.getKey())));
        Map<String, Object> out = new LinkedHashMap<>();
        for(var e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** {@code new int[n]}. */
    static List<Object> zeros(int n){
        return new ArrayList<>(Collections.nCopies(n, (Object)0));
    }

    /** {@code new boolean[n]}. */
    static List<Object> falses(int n){
        return new ArrayList<>(Collections.nCopies(n, (Object)false));
    }

    /** A deep copy of a mutable value, so two fields never share a list. */
    static Object copyValue(Object value){
        return Mut.thawValue(Mut.freezeValue(value));
    }
}
