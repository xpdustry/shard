package com.xpdustry.shard.content.epoch;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.bake.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * A frozen content snapshot built from a baked pack: per type, a dense array of definitions indexed by wire id, a
 * name index, and the {@linkplain ContentHash content hash}. Immutable, so worlds share it across threads.
 */
public final class Epoch implements ContentEpoch{
    private static volatile Epoch vanilla;

    private final Baked baked;
    private final EnumMap<ContentType, ContentDef[]> byId = new EnumMap<>(ContentType.class);
    private final EnumMap<ContentType, Map<String, ContentDef>> byName = new EnumMap<>(ContentType.class);
    private final String hash;
    private final Epoch base;

    private Epoch(Baked baked, Epoch base){
        this.baked = baked;
        this.base = base;
        for(ContentType type : ContentType.values()){
            List<Struct> structs = baked.of(type);
            List<String> names = baked.names(type);
            List<String> ids = baked.loaded().pack().ids().of(type);
            ContentDef[] defs = new ContentDef[structs.size()];
            Map<String, ContentDef> index = new HashMap<>();
            for(int id = 0; id < defs.length; id++){
                Struct fields = structs.get(id);
                if(fields == null) throw new IllegalArgumentException(type.yamlName() + " " + names.get(id) + " was not baked");
                // ids.yml lists what vanilla clients have; the loader appends added content after it
                defs[id] = Defs.create(this, type, id, names.get(id), fields, id >= ids.size());
                index.put(names.get(id), defs[id]);
            }
            byId.put(type, defs);
            byName.put(type, index);
        }
        for(ContentDef[] defs : byId.values()){
            for(ContentDef def : defs) ((Defs.Def)def).link();
        }
        hash = ContentHash.of(baked);
    }

    /** The epoch of baked content; every slot must be baked. */
    public static Epoch of(Baked baked){
        return new Epoch(baked, null);
    }

    /**
     * The epoch a content patch makes of {@code base} ({@code content.patch.DataPatcher}): {@code baked} has
     * {@code base}'s ids, names and kinds, so files, streams and codecs of {@code base} read and write it unchanged.
     * A patch that changes nothing gives {@code base} itself.
     */
    public static Epoch patched(Epoch base, Baked baked){
        Epoch root = base.base();
        var epoch = new Epoch(baked, root);
        return epoch.hash.equals(root.hash) ? root : epoch;
    }

    /** The unpatched epoch this one was patched from, or this one. */
    public Epoch base(){
        return base == null ? this : base;
    }

    /** Whether this epoch is a content patch of another. */
    public boolean isPatched(){
        return base != null;
    }

    /**
     * Whether a world of {@code content} is one this epoch's files and streams serve: this content, or a patch of it,
     * which keeps its ids and names.
     */
    public boolean serves(ContentEpoch content){
        if(content.hash().equals(hash)) return true;
        return content instanceof Epoch e && e.base().hash.equals(base().hash);
    }

    /** Loads, bakes and freezes {@code pack}, throwing a {@link ContentException} with every error. */
    public static Epoch load(Pack pack){
        var diagnostics = new Diagnostics();
        Epoch epoch = load(pack, diagnostics);
        diagnostics.check();
        return epoch;
    }

    /** Loads, bakes and freezes {@code pack}; on errors, reports them to {@code diagnostics} and returns null. */
    public static Epoch load(Pack pack, Diagnostics diagnostics){
        int before = diagnostics.size();
        Loaded loaded = Loader.load(pack, diagnostics);
        if(diagnostics.size() > before) return null;
        Baked baked = Bake.bake(loaded, diagnostics);
        if(diagnostics.size() > before) return null;
        return of(baked);
    }

    /** The vanilla v160.7 epoch, built once. */
    public static Epoch vanilla(){
        Epoch e = vanilla;
        if(e == null){
            synchronized(Epoch.class){
                e = vanilla;
                if(e == null) vanilla = e = load(Pack.vanilla());
            }
        }
        return e;
    }

    /** The baked structs this epoch was built from. */
    public Baked baked(){
        return baked;
    }

    public Schema schema(){
        return baked.schema();
    }

    /** The pack this epoch was built from. */
    public Pack pack(){
        return baked.loaded().pack();
    }

    @Override
    public String hash(){
        return hash;
    }

    @Override
    public int count(ContentType type){
        return byId.get(type).length;
    }

    @Override
    public ContentDef get(ContentType type, int id){
        return byId.get(type)[id];
    }

    @Override
    public ContentDef find(ContentType type, String name){
        return byName.get(type).get(name);
    }

    @Override
    public int id(ContentType type, String name){
        ContentDef def = find(type, name);
        return def == null ? -1 : def.id();
    }

    /** Every definition of {@code type}, in id order. */
    public List<ContentDef> all(ContentType type){
        return List.of(byId.get(type));
    }

    @Override
    public List<String> names(ContentType type){
        return Collections.unmodifiableList(baked.names(type));
    }

    @Override
    public List<String> unitCommands(){
        return pack().ids().list("unit-command");
    }

    @Override
    public List<String> unitStances(){
        return pack().ids().list("unit-stance");
    }

    @Override
    public List<String> sectors(){
        return ClientTables.v160_7().sectors();
    }

    @Override
    public List<String> planets(){
        return ClientTables.v160_7().planets();
    }

    @Override
    public List<String> logicIds(ContentType type){
        return ClientTables.v160_7().logicIds(type);
    }

    @Override
    public int blocks(){
        return count(ContentType.BLOCK);
    }

    @Override
    public BlockDef block(int id){
        return (BlockDef)get(ContentType.BLOCK, id);
    }

    @Override
    public int items(){
        return count(ContentType.ITEM);
    }

    @Override
    public int liquids(){
        return count(ContentType.LIQUID);
    }

    @Override
    public int units(){
        return count(ContentType.UNIT);
    }

    @Override
    public UnitDef unit(int id){
        return (UnitDef)get(ContentType.UNIT, id);
    }

    @Override
    public String toString(){
        return "Epoch[" + pack().name() + ", " + hash + "]";
    }
}
