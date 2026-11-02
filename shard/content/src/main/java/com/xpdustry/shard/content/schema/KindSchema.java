package com.xpdustry.shard.content.schema;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * A kind: one vanilla class that content uses ({@code conveyor} is {@code mindustry.world.blocks.distribution.Conveyor}),
 * with its parent kind, its fields and its class defaults. Block kinds also name the vanilla {@code Building} class
 * their buildings are; {@code :sim}'s kind registry binds the kind key to Shard's behaviour class.
 */
public final class KindSchema{
    private final Schema schema;
    private final String key;
    private final String vanillaClass, parentKey, build;
    private final boolean isAbstract;
    private final ContentType ownType;
    final List<FieldSpec> declared = new ArrayList<>();
    final Node.Mapping overrides;
    private KindSchema parent;
    private List<FieldSpec> fields, slots;
    private Map<String, FieldSpec> byName;
    private volatile Object[] defaults;
    private boolean binding;
    private int depth = -1;

    KindSchema(Schema schema, String key, String vanillaClass, String parentKey, String build, boolean isAbstract, ContentType ownType,
               Node.Mapping overrides){
        this.schema = schema;
        this.key = key;
        this.vanillaClass = vanillaClass;
        this.parentKey = parentKey;
        this.build = build;
        this.isAbstract = isAbstract;
        this.ownType = ownType;
        this.overrides = overrides;
    }

    String parentKey(){
        return parentKey;
    }

    boolean linked(){
        return slots != null;
    }

    /**
     * Lays out slots: the parent's first, in its order, then the declared fields. A declared field with an inherited
     * name hides the inherited one, as in Java: it takes a slot of its own, the name finds it from here down, and the
     * hidden one keeps the value the parent kind's code reads ({@code ForceProjector.breakSound} hides
     * {@code Block.breakSound}). The parent is linked.
     */
    void link(KindSchema parent){
        this.parent = parent;
        List<FieldSpec> all = new ArrayList<>();
        if(parent != null) all.addAll(parent.slots);
        all.addAll(declared);
        slots = List.copyOf(all);
        var map = new HashMap<String, FieldSpec>();
        for(FieldSpec f : slots) map.put(f.name(), f);
        byName = map;
        List<FieldSpec> visible = new ArrayList<>();
        for(FieldSpec f : slots) if(map.get(f.name()) == f) visible.add(f);
        fields = List.copyOf(visible);
    }

    /** The slot of the next declared field. */
    int nextSlot(){
        return (parent == null ? 0 : parent.slots.size()) + declared.size();
    }

    public Schema schema(){
        return schema;
    }

    /** The kebab-case kind key, {@code item-turret}. */
    public String key(){
        return key;
    }

    public KindKey kindKey(){
        return KindKey.of(key);
    }

    /** The vanilla class, {@code mindustry.world.blocks.defense.turrets.ItemTurret}. */
    public String vanillaClass(){
        return vanillaClass;
    }

    /** The vanilla class's simple name, as vanilla's patcher and {@code ClassMap} name it ({@code ItemTurret}). */
    public String vanillaName(){
        String simple = vanillaClass.substring(vanillaClass.lastIndexOf('.') + 1);
        return simple.substring(simple.lastIndexOf('$') + 1);
    }

    /**
     * For block kinds, the vanilla building class ({@code world.blocks.defense.turrets.ItemTurret$ItemTurretBuild}),
     * inherited when the kind doesn't name one; null for other kinds.
     */
    public String buildClass(){
        for(KindSchema k = this; k != null; k = k.parent){
            if(k.build != null) return k.build;
        }
        return null;
    }

    /**
     * For block kinds, the kind whose class declares the building class: the farthest ancestor with the same one
     * ({@code item-turret} for {@code ItemTurretBuild}, {@code block} for a plain {@code Building}). Null for other
     * kinds. Simple names alone collide ({@code DuctBridge$DuctBridgeBuild}, {@code DirectionLiquidBridge$DuctBridgeBuild}).
     */
    public KindSchema buildKind(){
        String b = buildClass();
        if(b == null) return null;
        KindSchema k = this;
        while(k.parent != null && b.equals(k.parent.buildClass())) k = k.parent;
        return k;
    }

    public KindSchema parent(){
        return parent;
    }

    public boolean isAbstract(){
        return isAbstract;
    }

    /** The content type whose root kind this kind is or extends, or null for sub-object kinds (weapons, parts…). */
    public ContentType contentType(){
        for(KindSchema k = this; k != null; k = k.parent){
            if(k.ownType != null) return k.ownType;
        }
        return null;
    }

    /** True if this kind is {@code other} or extends it. */
    public boolean isA(KindSchema other){
        for(KindSchema k = this; k != null; k = k.parent){
            if(k == other) return true;
        }
        return false;
    }

    public boolean isA(String key){
        for(KindSchema k = this; k != null; k = k.parent){
            if(k.key.equals(key)) return true;
        }
        return false;
    }

    /** Steps from the root kind, for ordering kinds parents-first. */
    public int depth(){
        if(depth < 0) depth = parent == null ? 0 : parent.depth() + 1;
        return depth;
    }

    /** Every field a name reaches, inherited first. Hidden fields are left out. */
    public List<FieldSpec> fields(){
        return fields;
    }

    /** Every slot of a struct of this kind, hidden fields included; a field's index here is its slot. */
    public List<FieldSpec> slots(){
        return slots;
    }

    /** The fields this kind declares. */
    public List<FieldSpec> declaredFields(){
        return List.copyOf(declared);
    }

    /** The field called {@code name}, declared here or inherited, or null. */
    public FieldSpec field(String name){
        return byName.get(name);
    }

    /**
     * The class defaults: what a fresh instance of the vanilla class holds, per slot. The parent's defaults, then this
     * kind's declared field defaults, then its {@code defaults:} overrides.
     */
    public Object[] defaults(){
        Object[] d = defaults;
        if(d == null){
            synchronized(schema){
                d = defaults;
                if(d == null){
                    if(binding) throw new IllegalStateException("the defaults of kind " + key + " contain an object of the same kind");
                    binding = true;
                    try{
                        defaults = d = schema.bindDefaults(this);
                    }finally{
                        binding = false;
                    }
                }
            }
        }
        return d;
    }

    /** A struct of this kind holding only class defaults. */
    public Struct defaultStruct(){
        return new Struct(this, defaults().clone());
    }

    @Override
    public String toString(){
        return key;
    }
}
