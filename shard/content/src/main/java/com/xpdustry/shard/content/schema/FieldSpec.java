package com.xpdustry.shard.content.schema;

import com.xpdustry.shard.content.yaml.*;

/**
 * One field of a kind, as {@code kinds.yml} declares it: its YAML name (vanilla's field name), type, tag, whether
 * vanilla's patcher refuses it ({@code @NoPatch}), and its slot in every {@link Struct} of the declaring kind and its
 * subkinds. Fields are inherited by slot: a field declared by {@code block} has the same slot in every block kind.
 */
public final class FieldSpec{
    private final String name, java;
    private final FieldType type;
    private final Tag tag;
    private final boolean noPatch, extracted;
    private final KindSchema owner;
    private final int slot;
    final Node defaultNode;

    FieldSpec(String name, String java, FieldType type, Tag tag, boolean noPatch, boolean extracted, KindSchema owner, int slot,
              Node defaultNode){
        this.name = name;
        this.java = java == null ? name : java;
        this.type = type;
        this.tag = tag;
        this.noPatch = noPatch;
        this.extracted = extracted;
        this.owner = owner;
        this.slot = slot;
        this.defaultNode = defaultNode;
    }

    public String name(){
        return name;
    }

    /**
     * The vanilla field's Java name, which patches use. It differs from {@link #name()} where YAML can't use the Java
     * name ({@code parent_} is {@code SteamVent.parent}, since {@code parent} is a prototype key) or vanilla's patcher
     * has its own ({@code consumes} is {@code consumeBuilder}).
     */
    public String javaName(){
        return java;
    }

    public FieldType type(){
        return type;
    }

    public Tag tag(){
        return tag;
    }

    /** Vanilla's {@code @NoPatch}: a world may not change it, and a patch can't carry it. */
    public boolean noPatch(){
        return noPatch;
    }

    /**
     * True when vanilla derives this field with code Shard doesn't port (campaign planets, status reactions): the pack
     * holds vanilla's baked value and the bake leaves it alone.
     */
    public boolean extracted(){
        return extracted;
    }

    /** The kind that declares it. */
    public KindSchema owner(){
        return owner;
    }

    public int slot(){
        return slot;
    }

    @Override
    public String toString(){
        return owner.key() + "." + name + ": " + type.name();
    }
}
