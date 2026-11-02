package com.xpdustry.shard.tools.extract;

import arc.audio.*;
import arc.graphics.*;
import arc.math.*;
import arc.struct.*;
import mindustry.ai.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.entities.bullet.*;
import mindustry.entities.part.DrawPart.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.meta.*;

import java.lang.reflect.*;

/**
 * The schema type of a field, as written in {@code kinds.yml}: {@code float}, {@code item}, {@code list<obj:weapon>},
 * {@code map<item,bullet>}… {@link #of} derives it from the Java field type; null means "not representable".
 */
sealed interface FieldType{
    String name();

    /** {@code bool int long float double string color}. */
    record Scalar(String name) implements FieldType{
    }

    /** An enum, written by constant name. */
    record EnumType(Class<?> type) implements FieldType{
        public String name(){
            return "enum:" + type.getSimpleName();
        }
    }

    /**
     * A content reference by name: {@code item block bullet liquid status unit weather planet sector unit-command
     * unit-stance}, or {@code content} for any type, written {@code type:name}.
     */
    record Ref(String name, ContentType type) implements FieldType{
    }

    /**
     * A named static ({@code fx:none}). {@code fx}, {@code sound} and {@code interp} also take inline objects, and
     * {@code progress} takes hooks (composed progress lambdas).
     */
    record Named(String prefix) implements FieldType{
        public String name(){
            return prefix;
        }
    }

    /**
     * Values with their own shape: {@code entity} (a unit's entity class), {@code ai} (a controller factory),
     * {@code hook} (a lambda), {@code attributes}, {@code attribute}, {@code stack}, {@code stacks},
     * {@code liquid-stack}, {@code liquid-stacks}, {@code payload-stack}, {@code team} (by id).
     */
    record Special(String name) implements FieldType{
    }

    record ListType(FieldType element) implements FieldType{
        public String name(){
            return "list<" + element.name() + ">";
        }
    }

    /** An unordered set, written as a list sorted by content id or name. */
    record SetType(FieldType element) implements FieldType{
        public String name(){
            return "set<" + element.name() + ">";
        }
    }

    /** A map, written sorted by key (content id or name). */
    record MapType(FieldType key, FieldType value) implements FieldType{
        public String name(){
            return "map<" + key.name() + "," + value.name() + ">";
        }
    }

    /** A polymorphic sub-object, written as a mapping with {@code kind}; {@code base} is the field's declared class. */
    record Obj(Class<?> base) implements FieldType{
        public String name(){
            return "obj:" + Names.kind(base);
        }
    }

    FieldType BOOL = new Scalar("bool"), INT = new Scalar("int"), LONG = new Scalar("long"), FLOAT = new Scalar("float"),
        DOUBLE = new Scalar("double"), STRING = new Scalar("string"), COLOR = new Scalar("color"), HOOK = new Special("hook"),
        STACK = new Special("stack"), STACKS = new Special("stacks"), LIQUID_STACK = new Special("liquid-stack"),
        LIQUID_STACKS = new Special("liquid-stacks"), PAYLOAD_STACK = new Special("payload-stack"),
        ATTRIBUTE = new Special("attribute"), ATTRIBUTES = new Special("attributes"), ENTITY = new Special("entity"),
        AI = new Special("ai"), TEAM = new Special("team");

    /** The type of {@code field}, or null when it has no YAML form. */
    static FieldType of(Field field){
        if(field.getDeclaringClass() == UnitType.class){
            switch(field.getName()){
                case "constructor" -> { return ENTITY; }
                case "controller", "aiController" -> { return AI; }
                default -> {}
            }
        }
        return of(field.getGenericType());
    }

    static FieldType of(Type type){
        if(type instanceof Class<?> c) return ofClass(c);
        if(type instanceof ParameterizedType p){
            Class<?> raw = (Class<?>)p.getRawType();
            Type[] args = p.getActualTypeArguments();
            if(raw == Seq.class){
                if(args[0] == ItemStack.class) return STACKS;
                if(args[0] == LiquidStack.class) return LIQUID_STACKS;
                return list(of(args[0]));
            }
            if(raw == ObjectSet.class || raw == OrderedSet.class || raw == EnumSet.class) return set(of(args[0]));
            if(raw == ObjectMap.class || raw == OrderedMap.class){
                FieldType k = of(args[0]), v = of(args[1]);
                return k == null || v == null || !keyable(k) ? null : new MapType(k, v);
            }
            if(raw == ObjectFloatMap.class){
                FieldType k = of(args[0]);
                return k == null || !keyable(k) ? null : new MapType(k, FLOAT);
            }
            if(raw == ObjectIntMap.class){
                FieldType k = of(args[0]);
                return k == null || !keyable(k) ? null : new MapType(k, INT);
            }
            if(Names.functional(raw)) return HOOK;
            return ofClass(raw);
        }
        if(type instanceof WildcardType w) return of(w.getUpperBounds()[0]);
        if(type instanceof TypeVariable<?> v) return of(v.getBounds()[0]);
        if(type instanceof GenericArrayType g) return list(of(g.getGenericComponentType()));
        return null;
    }

    private static FieldType ofClass(Class<?> c){
        if(c == boolean.class || c == Boolean.class) return BOOL;
        if(c == int.class || c == short.class || c == byte.class || c == Integer.class) return INT;
        if(c == long.class) return LONG;
        if(c == float.class || c == Float.class) return FLOAT;
        if(c == double.class) return DOUBLE;
        if(c == String.class || c == char.class) return STRING;
        if(c == Color.class) return COLOR;
        if(c.isEnum()) return new EnumType(c);
        if(c == ItemStack.class) return STACK;
        if(c == ItemStack[].class) return STACKS;
        if(c == LiquidStack.class) return LIQUID_STACK;
        if(c == LiquidStack[].class) return LIQUID_STACKS;
        if(c == PayloadStack.class) return PAYLOAD_STACK;
        if(c == Attribute.class) return ATTRIBUTE;
        if(c == Attributes.class) return ATTRIBUTES;
        if(c == Team.class) return TEAM;
        if(c.isArray()){
            if(TextureRegionLike.is(c)) return null;
            return list(ofClass(c.getComponentType()));
        }
        if(Content.class.isAssignableFrom(c)) return ref(c);
        if(Effect.class.isAssignableFrom(c)) return new Named("fx");
        if(Sound.class.isAssignableFrom(c)) return new Named("sound");
        if(Interp.class.isAssignableFrom(c)) return new Named("interp");
        if(PartProgress.class.isAssignableFrom(c)) return new Named("progress");
        if(c == Blending.class) return new Named("blending");
        if(c == BuildVisibility.class) return new Named("visibility");
        if(c == CacheLayer.class) return new Named("cache");
        if(c == Units.Sortf.class) return new Named("sort");
        if(c == Pathfinder.PathCost.class) return new Named("cost");
        if(Names.functional(c)) return HOOK;
        if(TextureRegionLike.is(c)) return null;
        // live entities and buildings are game state, never content
        if(Entityc.class.isAssignableFrom(c) || Building.class.isAssignableFrom(c)) return null;
        if(c.getName().startsWith("mindustry.") || c.getName().startsWith("arc.math.geom.")) return new Obj(c);
        return null;
    }

    private static FieldType ref(Class<?> c){
        if(Item.class.isAssignableFrom(c)) return new Ref("item", ContentType.item);
        if(Block.class.isAssignableFrom(c)) return new Ref("block", ContentType.block);
        if(BulletType.class.isAssignableFrom(c)) return new Ref("bullet", ContentType.bullet);
        if(Liquid.class.isAssignableFrom(c)) return new Ref("liquid", ContentType.liquid);
        if(StatusEffect.class.isAssignableFrom(c)) return new Ref("status", ContentType.status);
        if(UnitType.class.isAssignableFrom(c)) return new Ref("unit", ContentType.unit);
        if(Weather.class.isAssignableFrom(c)) return new Ref("weather", ContentType.weather);
        if(Planet.class.isAssignableFrom(c)) return new Ref("planet", ContentType.planet);
        if(SectorPreset.class.isAssignableFrom(c)) return new Ref("sector", ContentType.sector);
        if(UnitCommand.class.isAssignableFrom(c)) return new Ref("unit-command", ContentType.unitCommand);
        if(UnitStance.class.isAssignableFrom(c)) return new Ref("unit-stance", ContentType.unitStance);
        if(MappableContent.class.isAssignableFrom(c)) return new Ref("content", null);
        return null;
    }

    private static FieldType list(FieldType element){
        return element == null ? null : new ListType(element);
    }

    private static FieldType set(FieldType element){
        return element == null ? null : new SetType(element);
    }

    private static boolean keyable(FieldType type){
        return type instanceof Ref || type instanceof EnumType || type == STRING || type == INT || type == ATTRIBUTE;
    }

    /** Graphics handles that only a client loads. */
    final class TextureRegionLike{
        static boolean is(Class<?> c){
            while(c.isArray()) c = c.getComponentType();
            return arc.graphics.g2d.TextureRegion.class.isAssignableFrom(c) || c == Texture.class
                || c.getName().startsWith("arc.scene.");
        }
    }
}
