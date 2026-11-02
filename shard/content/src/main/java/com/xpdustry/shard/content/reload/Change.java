package com.xpdustry.shard.content.reload;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;

/**
 * One top-level field of one content object that a reload changes, with the field's tag: {@code server} changes apply
 * at a tick boundary, {@code shared} and {@code client} ones wait for the next world load. Added and removed content
 * has a null field and counts as shared.
 */
public record Change(ContentType type, String name, String field, Tag tag){
    @Override
    public String toString(){
        return type.yamlName() + " " + name + (field == null ? "" : "." + field) + " (" + tag.name().toLowerCase(java.util.Locale.ROOT) + ")";
    }
}
