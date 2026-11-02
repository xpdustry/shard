package com.xpdustry.shard.content.load;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * A content object as its prototype wrote it, after inheritance and typing: the class defaults of its kind plus the
 * fields the YAML set. The bake derives the rest ({@code init()}).
 *
 * @param added whether the content is new ({@code wire: added}): vanilla clients don't have it
 */
public record Authored(ContentType type, int id, String name, Struct fields, boolean added, Mark mark){
    public KindSchema kind(){
        return fields.kind();
    }

    /**
     * The {@code @NoPatch} fields this object writes differently from {@code before} ({@code size}, {@code hasItems}…):
     * vanilla's patcher refuses them. {@code consumes} is left out: patches rebuild consumers through their own key.
     * Derived {@code @NoPatch} values aren't authored, so they follow whatever they derive from.
     */
    public List<FieldSpec> noPatchChanges(Authored before){
        List<FieldSpec> out = new ArrayList<>();
        if(before.kind() != kind()) return out;
        for(FieldSpec f : kind().slots()){
            if(f.noPatch() && !f.name().equals("consumes") && !Objects.equals(before.fields.get(f), fields.get(f))) out.add(f);
        }
        return out;
    }
}
