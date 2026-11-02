package com.xpdustry.shard.tools.extract;

import mindustry.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.world.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * The schema: one kind per vanilla class that content uses, with its fields, their types and tags, and the class
 * defaults (the values of a fresh instance, built in a scratch {@link ContentLoader} so it takes no vanilla ids).
 */
final class Kinds{
    /** Fields that are not data: ids, names, UI state, views of other fields, behaviour that belongs to the kind. */
    static final Set<String> excluded = Set.of(
        "Content.id", "Content.minfo", "Content.removed",
        "MappableContent.name",
        "UnlockableContent.stats", "UnlockableContent.techNode", "UnlockableContent.techNodes", "UnlockableContent.unlocked",
        "UnlockableContent.localizedName", "UnlockableContent.description", "UnlockableContent.details",
        "Block.buildType", "Block.configurations", "Block.consumers", "Block.optionalConsumers", "Block.nonOptionalConsumers",
        "Block.updateConsumers", "Block.consPower", "Block.barMap", "Block.lastConfig", "Block.subclass",
        "UnitType.sample",
        "Effect.id"
    );

    /** Non-public fields that hold data, by vanilla name → YAML name. */
    static final Map<String, String> extra = Map.of(
        "Block.consumeBuilder", "consumes",
        "StatusEffect.transitions", "transitions",
        // set by init() from the power consumer it replaces, which only the dynamic consumer's lambda reads
        "PayloadLoader.basePowerUse", "basePowerUse"
    );

    /** The reserved keys of a prototype document ({@code :content}'s {@code Prototype.reserved}). */
    static final Set<String> prototypeKeys = Set.of("type", "id", "parent", "abstract", "overlay", "remove", "wire");

    /**
     * Fields whose vanilla value comes from code Shard doesn't port (campaign planets, status reactions written as
     * lambdas in {@code init}). The pack carries their baked value as authored data, and the bake leaves them alone.
     */
    static final Set<String> extractedBaked = Set.of(
        "UnlockableContent.shownPlanets", "UnlockableContent.databaseTabs", "UnlockableContent.databaseTag",
        "UnlockableContent.databaseCategory",
        "StatusEffect.affinities", "StatusEffect.opposites", "StatusEffect.transitions"
    );

    final Names names;
    final Map<Class<?>, Kind> byClass = new LinkedHashMap<>();
    final Set<String> skipped = new TreeSet<>();
    private final ContentLoader scratch = new ContentLoader();
    private int freshCounter;

    /** The prefix of the names fresh instances are built with; content names must be unique, so each gets a number. */
    static final String placeholder = "shard-fresh-";

    Kinds(Names names){
        this.names = names;
    }

    static final class Kind{
        final Class<?> type;
        final String key;
        final Kind parent;
        final List<Prop> fields = new ArrayList<>();
        /** Every field, inherited first. */
        final List<Prop> all = new ArrayList<>();
        final boolean isAbstract;
        /** A fresh instance, or null when the class can't be built. */
        Object fresh;
        boolean freshTried;

        Kind(Class<?> type, Kind parent){
            this.type = type;
            this.key = Names.kind(type);
            this.parent = parent;
            this.isAbstract = Modifier.isAbstract(type.getModifiers()) || type.isInterface();
        }

        Prop prop(String name){
            for(Prop p : all) if(p.name.equals(name)) return p;
            return null;
        }

        boolean isA(Class<?> base){
            return base.isAssignableFrom(type);
        }
    }

    static final class Prop{
        final Field field;
        final String name;
        final FieldType type;
        final Kind owner;
        Tags.Tag tag;
        final boolean noPatch;
        final boolean extracted;

        Prop(Field field, String name, FieldType type, Kind owner){
            this.field = field;
            this.name = name;
            this.type = type;
            this.owner = owner;
            this.noPatch = field.isAnnotationPresent(NoPatch.class) || field.getDeclaringClass().isAnnotationPresent(NoPatch.class);
            this.extracted = extractedBaked.contains(field.getDeclaringClass().getSimpleName() + "." + field.getName());
        }

        Object get(Object target){
            try{
                return field.get(target);
            }catch(IllegalAccessException e){
                throw new IllegalStateException(field.toString(), e);
            }
        }
    }

    /** The kind of {@code type} (anonymous classes are their superclass), with its parent chain. */
    Kind kind(Class<?> type){
        type = Names.concrete(type);
        Kind existing = byClass.get(type);
        if(existing != null) return existing;
        Class<?> superclass = type.getSuperclass();
        Kind parent = superclass == null || superclass == Object.class ? null : kind(superclass);
        Kind kind = new Kind(type, parent);
        for(Kind other : byClass.values()){
            if(other.key.equals(kind.key)){
                throw new IllegalStateException("kind " + kind.key + " is both " + other.type.getName() + " and " + type.getName());
            }
        }
        byClass.put(type, kind);
        if(parent != null) kind.all.addAll(parent.all);
        List<Field> declared = new ArrayList<>(List.of(type.getDeclaredFields()));
        declared.sort(Comparator.comparing(Field::getName));
        for(Field f : declared){
            int m = f.getModifiers();
            String id = type.getSimpleName() + "." + f.getName();
            String yamlName = extra.get(id);
            if(yamlName == null){
                if(!Modifier.isPublic(m) || Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic()) continue;
                if(excluded.contains(id)) continue;
                yamlName = f.getName();
            }
            // prototype keys (type, id, parent…) and kind are not fields: SteamVent.parent is written parent_
            if(yamlName.equals("kind") || Content.class.isAssignableFrom(type) && prototypeKeys.contains(yamlName)) yamlName += "_";
            FieldType ft = FieldType.of(f);
            if(ft == null){
                skipped.add(id + " (" + f.getGenericType().getTypeName() + ")");
                continue;
            }
            f.setAccessible(true);
            var prop = new Prop(f, yamlName, ft, kind);
            prop.tag = Tags.of(prop);
            kind.fields.add(prop);
            // a field that hides an inherited one of the same name takes its place, as Arc's Json and the patcher see it
            int hidden = -1;
            for(int i = 0; i < kind.all.size(); i++) if(kind.all.get(i).name.equals(yamlName)) hidden = i;
            if(hidden >= 0) kind.all.set(hidden, prop);
            else kind.all.add(prop);
        }
        return kind;
    }

    /** A fresh instance of {@code kind}'s class: no-arg constructor, then {@code (String)}, then zeros and nulls. */
    Object fresh(Kind kind){
        if(kind.freshTried) return kind.fresh;
        kind.freshTried = true;
        if(kind.isAbstract) return null;
        ContentLoader previous = Vars.content;
        Vars.content = scratch;
        try{
            kind.fresh = construct(kind.type);
        }finally{
            Vars.content = previous;
        }
        return kind.fresh;
    }

    /** Runs {@code action} with the scratch loader as {@code Vars.content}, so content it creates takes no vanilla ids. */
    <T> T inScratch(java.util.function.Supplier<T> action){
        ContentLoader previous = Vars.content;
        Vars.content = scratch;
        try{
            return action.get();
        }finally{
            Vars.content = previous;
        }
    }

    private Object construct(Class<?> type){
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.<Constructor<?>>comparingInt(Constructor::getParameterCount).thenComparing(Constructor::toGenericString));
        for(Constructor<?> c : constructors){
            if(c.getParameterCount() == 0 && Modifier.isPublic(c.getModifiers())) return attempt(c);
        }
        for(Constructor<?> c : constructors){
            if(c.getParameterCount() == 1 && c.getParameterTypes()[0] == String.class && Modifier.isPublic(c.getModifiers())){
                Object o = attempt(c);
                if(o != null) return o;
            }
        }
        for(Constructor<?> c : constructors){
            if(!Modifier.isPublic(c.getModifiers())) continue;
            Object o = attempt(c);
            if(o != null) return o;
        }
        for(Constructor<?> c : constructors){
            Object o = attempt(c);
            if(o != null) return o;
        }
        return null;
    }

    private Object attempt(Constructor<?> c){
        try{
            c.setAccessible(true);
            Class<?>[] types = c.getParameterTypes();
            Object[] args = new Object[types.length];
            for(int i = 0; i < args.length; i++) args[i] = zero(types[i]);
            return c.newInstance(args);
        }catch(Throwable e){
            return null;
        }
    }

    private Object zero(Class<?> p){
        if(p == boolean.class) return false;
        if(p == float.class) return 0f;
        if(p == int.class) return 0;
        if(p == double.class) return 0d;
        if(p == long.class) return 0L;
        if(p == short.class) return (short)0;
        if(p == byte.class) return (byte)0;
        if(p == char.class) return (char)0;
        if(p == String.class) return placeholder + freshCounter++;
        if(p.isArray()) return java.lang.reflect.Array.newInstance(p.getComponentType(), 0);
        return null;
    }

    /** The vanilla {@code Building} class a block kind creates ({@code Block.initBuilding}'s search). */
    static Class<?> buildClass(Class<?> block){
        Class<?> current = Names.concrete(block);
        while(Block.class.isAssignableFrom(current)){
            for(Class<?> type : current.getDeclaredClasses()){
                if(Building.class.isAssignableFrom(type) && !type.isInterface()) return type;
            }
            current = current.getSuperclass();
        }
        return Building.class;
    }

    /** The content type whose root class is {@code type} (Block → block), or null. */
    static ContentType root(Class<?> type){
        for(ContentType t : ContentType.all){
            if(t.contentClass == type) return t;
        }
        return null;
    }

    /** Entity component interfaces that {@code UnitType.init} checks, by trait name. */
    static final Map<Class<?>, String> traits = new LinkedHashMap<>();

    static{
        traits.put(Mechc.class, "mech");
        traits.put(Legsc.class, "legs");
        traits.put(Crawlc.class, "crawl");
        traits.put(WaterMovec.class, "water-move");
        traits.put(WaterCrawlc.class, "water-crawl");
        traits.put(Tankc.class, "tank");
        traits.put(ElevationMovec.class, "elevation-move");
        traits.put(Payloadc.class, "payload");
        traits.put(TimedKillc.class, "timed-kill");
        traits.put(BuildingTetherc.class, "building-tether");
        traits.put(BlockUnitc.class, "block-unit");
    }
}
