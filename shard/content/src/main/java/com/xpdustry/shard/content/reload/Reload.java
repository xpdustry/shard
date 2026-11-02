package com.xpdustry.shard.content.reload;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;
import java.util.function.*;

/**
 * Builds reload candidates (ARCHITECTURE.md §5 "Reload", steps 1 and 2). A candidate is refused when it has:
 * <ul>
 * <li>load or bake errors, unknown fields among them (the loader reports every one);
 * <li>an authored change to a {@code @NoPatch} field ({@code size}, {@code hasItems}…), or to a content's kind:
 * neither can reach vanilla clients, and live buildings can't migrate;
 * <li>a block whose kind the simulation doesn't support.
 * </ul>
 * Adopting it (tick-boundary apply of {@code server} changes, staging the rest) is the caller's job; so is sending
 * the world patch ({@link com.xpdustry.shard.content.patch.Patchgen}) at the next world load.
 */
public final class Reload{
    private Reload(){
    }

    /**
     * Loads, bakes and checks {@code pack} against {@code current}.
     *
     * @param supported whether the simulation has a behaviour for a block. The simulation decides which key that is
     *     ({@code KindRegistry.key(block)}: {@link BlockDef#kind()} for tile kinds, {@link BlockDef#buildKind()} for
     *     buildings), so the whole block is passed.
     */
    public static Candidate candidate(Epoch current, Pack pack, Predicate<BlockDef> supported){
        var diagnostics = new Diagnostics();
        Epoch next = Epoch.load(pack, diagnostics);
        if(next == null) return new Candidate(null, diagnostics.list(), List.of());

        for(int id = 0; id < next.blocks(); id++){
            BlockDef block = next.block(id);
            if(!supported.test(block)){
                diagnostics.error(mark(next, block), "block " + block.name() + " has kind " + block.kind() + ", which the simulation doesn't support");
            }
        }

        List<Change> changes = new ArrayList<>();
        for(ContentType type : ContentType.values()){
            for(int id = 0; id < next.count(type); id++){
                ContentDef def = next.get(type, id);
                ContentDef old = current.find(type, def.name());
                if(old == null){
                    changes.add(new Change(type, def.name(), null, Tag.SHARED));
                    continue;
                }
                if(old.fields().kind() != def.fields().kind()){
                    diagnostics.error(mark(next, def), type.yamlName() + " " + def.name() + " changes kind from " + old.fields().kind().key()
                        + " to " + def.fields().kind().key());
                    continue;
                }
                noPatch(current, next, def, diagnostics);
                for(FieldSpec f : def.fields().kind().slots()){
                    if(!Objects.equals(old.fields().get(f), def.fields().get(f))) changes.add(new Change(type, def.name(), f.name(), f.tag()));
                }
            }
            for(int id = 0; id < current.count(type); id++){
                ContentDef old = current.get(type, id);
                if(next.find(type, old.name()) == null) changes.add(new Change(type, old.name(), null, Tag.SHARED));
            }
        }
        return new Candidate(next, diagnostics.list(), changes);
    }

    /** Refuses authored changes to {@code @NoPatch} fields ({@link Authored#noPatchChanges}). */
    private static void noPatch(Epoch current, Epoch next, ContentDef def, Diagnostics diagnostics){
        Authored now = next.baked().loaded().get(def.type(), def.name()), before = current.baked().loaded().get(def.type(), def.name());
        if(now == null || before == null) return;
        for(FieldSpec f : now.noPatchChanges(before)){
            diagnostics.error(now.mark(), def.type().yamlName() + " " + def.name() + " changes " + f.name() + " from " + before.fields().get(f)
                + " to " + now.fields().get(f) + ", which vanilla can't patch (@NoPatch)");
        }
    }

    private static Mark mark(Epoch epoch, ContentDef def){
        Authored a = epoch.baked().loaded().get(def.type(), def.name());
        return a == null ? Mark.none : a.mark();
    }
}
