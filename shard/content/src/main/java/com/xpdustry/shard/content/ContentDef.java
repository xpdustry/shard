package com.xpdustry.shard.content;

import com.xpdustry.shard.content.schema.*;

/**
 * A content object pinned by a {@link ContentEpoch}: its wire id, name and baked fields. The typed definitions
 * ({@link BlockDef}, {@link ItemDef}…) extend it with getters for the fields Shard reads most; every other field is in
 * {@link #fields()}, typed by the kind's schema.
 */
public interface ContentDef{
    /** The wire id, which is the client's content id. */
    int id();

    /** The content name; hoisted bullets have a path ({@code duo/ammoTypes.copper}). */
    String name();

    ContentType type();

    /**
     * Every field after baking, as a booted vanilla holds them. Hand-built definitions (test content) have none and
     * throw.
     */
    default Struct fields(){
        throw new UnsupportedOperationException(type() + " " + name() + " has no baked fields");
    }

    /**
     * Whether {@link #fields()} holds baked fields. Hand-built definitions (test content) have none: the getters that
     * read fields throw on them, so a reader that accepts hand-built content checks this first.
     */
    default boolean baked(){
        return false;
    }

    /** The kind: the vanilla class of the content ({@code item-turret}, {@code unit-type}…). */
    default KindKey kind(){
        return fields().kind().kindKey();
    }

    /** Whether the kind is {@code kind} or extends it, as vanilla's {@code instanceof} ({@code duo} is a {@code turret}). */
    default boolean isA(KindKey kind){
        return fields().kind().isA(kind.name());
    }

    /** Whether vanilla clients lack this content ({@code wire: added}); added content is sent as an asset. */
    default boolean added(){
        return false;
    }

    /**
     * {@code UnlockableContent.emojiChar()}: the char the client's icon font shows for this content, by name, or 0.
     * Bullets aren't unlockable content in vanilla, so mlog's {@code printchar} prints nothing for them.
     */
    default int emoji(){
        return 0;
    }
}
