package com.xpdustry.shard.oracle.dump;

import arc.struct.*;
import mindustry.ctype.*;
import com.xpdustry.shard.oracle.*;

import java.util.*;

import static mindustry.Vars.*;

/** content/&lt;type&gt;.json: every content object of a type, in id order, with every field by reflection. */
final class ContentDump{
    /** Nested objects deeper than this become {"$class": ..., "$depth": true}. */
    static final int depth = 10;

    static Object dump(ContentType type){
        Seq<Content> all = content.getBy(type);
        List<Object> entries = new ArrayList<>(all.size);
        for(Content c : all){
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", (int)c.id);
            entry.put("name", c instanceof MappableContent m ? m.name : null);
            entry.put("class", Views.className(c.getClass()));
            entry.put("fields", Views.fields(c, null, depth));
            entries.add(entry);
        }
        return Views.map("type", type.name(), "ordinal", type.ordinal(), "count", all.size, "entries", entries);
    }

    /** content-ids.json: names in id order per type; unnamed content (bullets) lists class names. */
    static Object ids(){
        Map<String, Object> out = new LinkedHashMap<>();
        for(ContentType type : ContentType.all){
            Seq<Content> all = content.getBy(type);
            if(all.isEmpty()) continue;
            List<String> names = new ArrayList<>(all.size);
            for(Content c : all) names.add(c instanceof MappableContent m ? m.name : Views.className(c.getClass()));
            out.put(type.name(), Views.map("ordinal", type.ordinal(), "count", all.size, "ids", names));
        }
        return out;
    }

    private ContentDump(){}
}
