package com.xpdustry.shard.content.schema;

import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * The type of a kind's field, as {@code kinds.yml} writes it ({@code float}, {@code item}, {@code list<obj:weapon>},
 * {@code map<item,bullet>}…). It decides how the binder reads a YAML node and what plain value the field holds:
 *
 * <ul>
 * <li>{@link Scalar}: {@code Boolean}, {@code Integer}, {@code Long}, {@code Float}, {@code Double}, {@code String};
 * colors are {@code "#rrggbbaa"} strings.
 * <li>{@link EnumType}, {@link Ref}, {@link Named}: strings (a constant, a content name or bullet id, a
 * {@code prefix:name} static). {@code fx}, {@code sound} and {@code interp} may also hold an inline {@link Struct},
 * {@code progress} and {@code interp} a {@code hook:} string.
 * <li>{@link Special}: see its javadoc.
 * <li>{@link ListType}, {@link SetType}: unmodifiable lists; {@link MapType}: unmodifiable ordered maps with string keys.
 * <li>{@link Obj}: a {@link Struct} of the base kind or a subkind.
 * </ul>
 */
public sealed interface FieldType{
    String name();

    enum Scalar implements FieldType{
        BOOL("bool"), INT("int"), LONG("long"), FLOAT("float"), DOUBLE("double"), STRING("string"), COLOR("color");

        private final String text;

        Scalar(String text){
            this.text = text;
        }

        @Override
        public String toString(){
            return text;
        }

        public String text(){
            return text;
        }
    }

    record EnumType(String enumName) implements FieldType{
        public String name(){
            return "enum:" + enumName;
        }
    }

    /**
     * A content reference by name. {@code type} is null for references to content Shard doesn't load ({@code planet},
     * {@code sector}, {@code unit-command}, {@code unit-stance}) and for {@code content}, written {@code type:name}.
     */
    record Ref(String name, ContentType type) implements FieldType{
    }

    /** A shared static by {@code prefix:name}: {@code fx sound interp progress blending visibility cache sort cost}. */
    record Named(String registry) implements FieldType{
        public String name(){
            return registry;
        }

        /** Whether an inline object may stand here ({@code fx}, {@code sound}, {@code interp}). */
        public boolean allowsObject(){
            return registry.equals("fx") || registry.equals("sound") || registry.equals("interp");
        }

        /** Whether a {@code hook:} may stand here ({@code progress}, {@code interp}). */
        public boolean allowsHook(){
            return registry.equals("progress") || registry.equals("interp");
        }
    }

    /**
     * Values with a shape of their own:
     * <ul>
     * <li>{@code entity}: a unit's entity class ({@code MechUnit}), see {@link Schema#entity(String)};
     * <li>{@code ai}: a controller kind ({@code flying-ai}) or a {@code hook:};
     * <li>{@code hook}: {@code hook:<name>}, a lambda the simulation implements by name, or an inline object;
     * <li>{@code stack}, {@code liquid-stack}, {@code payload-stack}: a one-entry map {@code {name: amount}};
     * <li>{@code stacks}, {@code liquid-stacks}: an ordered map {@code {copper: 30, lead: 25}};
     * <li>{@code attribute}: an attribute name; {@code attributes}: a map attribute → float;
     * <li>{@code team}: a team id.
     * </ul>
     */
    record Special(String name) implements FieldType{
    }

    record ListType(FieldType element) implements FieldType{
        public String name(){
            return "list<" + element.name() + ">";
        }
    }

    /** An unordered set, held as a list in canonical order (content id, enum ordinal, else text). */
    record SetType(FieldType element) implements FieldType{
        public String name(){
            return "set<" + element.name() + ">";
        }
    }

    record MapType(FieldType key, FieldType value) implements FieldType{
        public String name(){
            return "map<" + key.name() + "," + value.name() + ">";
        }
    }

    /** A polymorphic sub-object of kind {@code base} or one of its subkinds. */
    record Obj(String base) implements FieldType{
        public String name(){
            return "obj:" + base;
        }
    }

    Special ENTITY = new Special("entity"), AI = new Special("ai"), HOOK = new Special("hook"), STACK = new Special("stack"),
        STACKS = new Special("stacks"), LIQUID_STACK = new Special("liquid-stack"), LIQUID_STACKS = new Special("liquid-stacks"),
        PAYLOAD_STACK = new Special("payload-stack"), ATTRIBUTE = new Special("attribute"), ATTRIBUTES = new Special("attributes"),
        TEAM = new Special("team");

    Set<String> registries = Set.of("fx", "sound", "interp", "progress", "blending", "visibility", "cache", "sort", "cost");
    Set<String> specials = Set.of("entity", "ai", "hook", "stack", "stacks", "liquid-stack", "liquid-stacks", "payload-stack",
        "attribute", "attributes", "team");

    /** Parses a type name; throws {@link IllegalArgumentException} for an unknown one. */
    static FieldType parse(String text){
        text = text.trim();
        if(text.startsWith("list<") && text.endsWith(">")) return new ListType(parse(text.substring(5, text.length() - 1)));
        if(text.startsWith("set<") && text.endsWith(">")) return new SetType(parse(text.substring(4, text.length() - 1)));
        if(text.startsWith("map<") && text.endsWith(">")){
            String inner = text.substring(4, text.length() - 1);
            int depth = 0;
            for(int i = 0; i < inner.length(); i++){
                char c = inner.charAt(i);
                if(c == '<') depth++;
                else if(c == '>') depth--;
                else if(c == ',' && depth == 0) return new MapType(parse(inner.substring(0, i)), parse(inner.substring(i + 1)));
            }
            throw new IllegalArgumentException("map type without a value type: " + text);
        }
        if(text.startsWith("enum:")) return new EnumType(text.substring(5));
        if(text.startsWith("obj:")) return new Obj(text.substring(4));
        for(Scalar s : Scalar.values()) if(s.text.equals(text)) return s;
        if(registries.contains(text)) return new Named(text);
        if(specials.contains(text)) return new Special(text);
        for(ContentType t : ContentType.values()) if(t.yamlName().equals(text)) return new Ref(text, t);
        if(Set.of("planet", "sector", "unit-command", "unit-stance", "content").contains(text)) return new Ref(text, null);
        throw new IllegalArgumentException("unknown field type '" + text + "'");
    }
}
