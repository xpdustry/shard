package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.*;

/**
 * A content value in a config (vanilla passes the {@code Item}, {@code Block}… object itself): the content's type and
 * its id in the world's content. {@link Configs} dispatches it by type, as vanilla's {@code configured} does by
 * content class.
 */
public record ContentRef(ContentType type, int id){
    public static ContentRef item(int id){
        return new ContentRef(ContentType.ITEM, id);
    }

    public static ContentRef liquid(int id){
        return new ContentRef(ContentType.LIQUID, id);
    }

    public static ContentRef block(int id){
        return new ContentRef(ContentType.BLOCK, id);
    }

    /**
     * Content of a vanilla type no {@link ContentEpoch} holds (a {@code UnitCommand}, a {@code Planet}), by vanilla's
     * {@code ContentType} ordinal and its id: kept so a plan carrying it round-trips. No kind takes it, so
     * {@code configured} does nothing with it, as vanilla does on a block without a handler for its class.
     */
    public record Foreign(int vanillaType, int id){
    }
}
