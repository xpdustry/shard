package com.xpdustry.shard.content;

/** The typed, immutable definition of an item ({@code mindustry.type.Item}). */
public interface ItemDef extends ContentDef{
    @Override
    default ContentType type(){
        return ContentType.ITEM;
    }

    /** Build time per item, before {@code buildCostMultiplier}. */
    default float cost(){
        return fields().f("cost");
    }

    /** The drill tier needed to mine it. */
    default int hardness(){
        return fields().i("hardness");
    }

    default float flammability(){
        return fields().f("flammability");
    }

    default float explosiveness(){
        return fields().f("explosiveness");
    }

    default float radioactivity(){
        return fields().f("radioactivity");
    }

    default float charge(){
        return fields().f("charge");
    }

    /** Health per block area that requiring this item adds ({@code Block.init}). */
    default float healthScaling(){
        return fields().f("healthScaling");
    }

    default boolean buildable(){
        return fields().b("buildable");
    }

    default boolean hidden(){
        return fields().b("hidden");
    }

    /** Unloaders and sorters take it last. */
    default boolean lowPriority(){
        return fields().b("lowPriority");
    }
}
