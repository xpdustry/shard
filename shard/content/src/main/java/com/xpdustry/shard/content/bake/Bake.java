package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * Computes what vanilla's {@code init()} and {@code postInit()} derive (ARCHITECTURE.md §5 "Baking"), so that a baked
 * struct holds the values a booted vanilla client has: block health from requirements, build time, liquid capacity,
 * consumer filters, unit ranges and mirrored weapons, bullet ranges, and so on.
 *
 * <p>Vanilla's order is kept: {@code init()} on every content type in {@code ContentType} order, each in id order,
 * then {@code postInit()} the same way. Blocks therefore initialize before bullets (turrets see un-initialized ammo),
 * and content an init creates ({@code BuildTurret}'s unit) is initialized with its type.
 *
 * <p>Each kind's init is ported as one {@link Init} that calls {@code sup} where vanilla calls {@code super.init()}.
 */
public final class Bake{
    /** One kind's {@code init()}: {@code sup} runs the parent kind's. */
    @FunctionalInterface
    interface Init{
        void run(Bake bake, Mut self, Runnable sup);
    }

    private static final Map<String, Init> inits = new HashMap<>(), postInits = new HashMap<>();

    static{
        Inits.register();
    }

    static void init(String kind, Init init){
        if(inits.put(kind, init) != null) throw new IllegalStateException("two inits for " + kind);
    }

    static void postInit(String kind, Init init){
        if(postInits.put(kind, init) != null) throw new IllegalStateException("two postInits for " + kind);
    }

    final Loaded loaded;
    final Schema schema;
    final Diagnostics diagnostics;
    private final EnumMap<ContentType, List<Mut>> content = new EnumMap<>(ContentType.class);
    private final EnumMap<ContentType, List<String>> names = new EnumMap<>(ContentType.class);
    private final EnumMap<ContentType, Map<String, Integer>> ids = new EnumMap<>(ContentType.class);
    private final Map<Mut, String> contentNames = new IdentityHashMap<>();
    private final Map<Mut, ContentType> contentTypes = new IdentityHashMap<>();
    /** {@code BulletType.cachedDps}: the first estimate is kept, as vanilla caches it. */
    final Map<Mut, Float> cachedDps = new IdentityHashMap<>();

    private Bake(Loaded loaded, Diagnostics diagnostics){
        this.loaded = loaded;
        this.schema = loaded.schema();
        this.diagnostics = diagnostics;
        for(ContentType type : ContentType.values()){
            List<Mut> list = new ArrayList<>();
            for(Authored a : loaded.of(type)){
                Mut m = a == null ? null : Mut.thaw(a.fields());
                if(m != null){
                    contentNames.put(m, a.name());
                    contentTypes.put(m, type);
                }
                list.add(m);
            }
            content.put(type, list);
            names.put(type, new ArrayList<>(loaded.names(type)));
            Map<String, Integer> map = new HashMap<>();
            for(int i = 0; i < names.get(type).size(); i++) map.put(names.get(type).get(i), i);
            ids.put(type, map);
        }
    }

    /** Bakes {@code loaded}, throwing a {@link ContentException} when it can't. */
    public static Baked bake(Loaded loaded){
        var diagnostics = new Diagnostics();
        Baked baked = bake(loaded, diagnostics);
        diagnostics.check();
        return baked;
    }

    public static Baked bake(Loaded loaded, Diagnostics diagnostics){
        return new Bake(loaded, diagnostics).run();
    }

    private Baked run(){
        pass(inits);
        pass(postInits);
        EnumMap<ContentType, List<Struct>> out = new EnumMap<>(ContentType.class);
        for(ContentType type : ContentType.values()){
            List<Struct> list = new ArrayList<>();
            List<Mut> muts = content.get(type);
            for(int i = 0; i < muts.size(); i++){
                Mut m = muts.get(i);
                if(m == null){
                    diagnostics.error(Mark.none, type.yamlName() + " " + names.get(type).get(i) + " is listed in ids.yml, but no prototype "
                        + "defines it and the bake didn't derive it");
                    list.add(null);
                }else{
                    list.add(m.freeze());
                }
            }
            out.put(type, Collections.unmodifiableList(list));
        }
        return new Baked(loaded, out, names);
    }

    private void pass(Map<String, Init> table){
        for(ContentType type : ContentType.values()){
            List<Mut> list = content.get(type);
            // an init may derive content of a later type; the loop sees it when it gets there, as vanilla's does
            for(int i = 0; i < list.size(); i++){
                Mut m = list.get(i);
                if(m != null) run(table, m, m.kind);
            }
        }
    }

    private void run(Map<String, Init> table, Mut self, KindSchema from){
        for(KindSchema k = from; k != null; k = k.parent()){
            Init init = table.get(k.key());
            if(init != null){
                KindSchema parent = k.parent();
                init.run(this, self.view(k), () -> run(table, self, parent));
                return;
            }
        }
    }

    /** Runs {@code self}'s init as kind {@code kind} would (sub-objects: weapons, abilities, consumers…). */
    void init(Mut self){
        run(inits, self, self.kind);
    }

    // lookups for inits

    Mut get(ContentType type, String name){
        Integer id = ids.get(type).get(name);
        return id == null ? null : content.get(type).get(id);
    }

    /** The name of a content object being baked. */
    String name(Mut content){
        String name = contentNames.get(content.base);
        if(name == null) throw new IllegalArgumentException("not content: " + content.kind.key());
        return name;
    }

    ContentType type(Mut content){
        return contentTypes.get(content.base);
    }

    int id(ContentType type, String name){
        return ids.get(type).getOrDefault(name, -1);
    }

    String name(ContentType type, int id){
        return names.get(type).get(id);
    }

    int count(ContentType type){
        return content.get(type).size();
    }

    /** Content of {@code type} in id order (slots for derived content may still be null). */
    List<Mut> all(ContentType type){
        return content.get(type);
    }

    /** A fresh sub-object of kind {@code key}. */
    Mut create(String key){
        KindSchema kind = schema.kind(key);
        if(kind == null) throw new IllegalStateException("the schema has no kind " + key);
        return Mut.create(kind);
    }

    /**
     * Adds content an init creates ({@code new UnitType(...)} in {@code BuildTurret.init}). It takes its {@code ids.yml}
     * id when listed, else the next free one, as vanilla appends it.
     */
    void derive(ContentType type, String name, Mut value){
        Integer id = ids.get(type).get(name);
        List<Mut> list = content.get(type);
        if(id == null){
            id = list.size();
            list.add(null);
            names.get(type).add(name);
            ids.get(type).put(name, id);
        }
        if(list.get(id) != null){
            diagnostics.error(Mark.none, "derived " + type.yamlName() + " " + name + " is also defined by a prototype");
            return;
        }
        list.set(id, value);
        contentNames.put(value, name);
        contentTypes.put(value, type);
    }
}
