package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

import java.util.*;

/** The {@link BuildingCodec}s by kind, and the dispatch that writes a building's map chunk. */
public final class BuildingCodecs{
    private final TreeMap<KindKey, BuildingCodec> codecs = new TreeMap<>();

    private BuildingCodecs(List<BuildingCodec> list){
        for(BuildingCodec codec : list){
            BuildingCodec previous = codecs.putIfAbsent(codec.kind(), codec);
            if(previous != null){
                throw new IllegalArgumentException("kind " + codec.kind() + " has two codecs: " + previous.getClass().getName()
                    + " and " + codec.getClass().getName());
            }
        }
    }

    /** Every registered codec on the class path. */
    public static BuildingCodecs builtIn(){
        return BuiltIn.CODECS;
    }

    /** Exactly these codecs, for tests and tools. */
    public static BuildingCodecs of(List<BuildingCodec> codecs){
        return new BuildingCodecs(codecs);
    }

    /** The codec of {@code kind}, or null. */
    public BuildingCodec get(KindKey kind){
        return codecs.get(kind);
    }

    /**
     * The chunk of {@code building} as a map section holds it after its length: the revision byte, the
     * {@link BuildingBase}, then its passthrough bytes if it is a {@link PassthroughBuilding}, else its kind's codec's.
     * Passthrough bytes must be from {@code content}, and {@code ids}' header must keep the ids of the header they were
     * read with; a passthrough building placed live, with none, writes vanilla's fresh body ({@link FreshBodies}).
     */
    public byte[] chunk(Building building, ContentEpoch content, ContentHeader.IdMapping ids){
        var out = new Writes();
        if(building instanceof PassthroughBuilding passthrough){
            // one placed live has no save bytes: vanilla's building of that block as tile.setBlock makes it
            FreshBodies.Body fresh = passthrough.hasBody() ? null : FreshBodies.of(building.block());
            if(!passthrough.hasBody() && fresh == null){
                throw new IllegalStateException("building " + building.block().name() + " was placed live and vanilla has no such block");
            }
            out.b(fresh != null ? fresh.revision() : passthrough.revision());
            BuildingBase.write(building, out, ids);
            out.b(fresh != null ? fresh.bytes() : passthrough.body(content));
            return out.toByteArray();
        }
        KindKey kind = building.world().kinds().key(building.block());
        BuildingCodec codec = codecs.get(kind);
        if(codec == null){
            throw new IllegalStateException("building " + building.block().name() + " (kind " + kind + ") has no codec");
        }
        out.b(codec.revision());
        BuildingBase.write(building, out, ids);
        codec.write(building, out, ids);
        return out.toByteArray();
    }

    /**
     * Reads a building of {@code block} inside a block payload ({@code writeAll}: its {@link BuildingBase}, then the
     * body of {@code kind} at {@code revision}) and returns those bytes, for {@code WireContext.readBuildingBody}. A kind
     * without a codec has no known layout, so it fails.
     */
    public byte[] readBody(Reads in, BlockDef block, KindKey kind, int revision){
        return readBody(in, block, kind, revision, null);
    }

    /**
     * {@link #readBody(Reads, BlockDef, KindKey, int)}, measuring a payload the carried building holds itself (a payload
     * conveyor's) in {@code context}, which is the caller's; without one such a payload can't be read past.
     */
    public byte[] readBody(Reads in, BlockDef block, KindKey kind, int revision, com.xpdustry.shard.wire.WireContext context){
        return readBody(in, block, kind, revision, context, null);
    }

    /**
     * {@link #readBody(Reads, BlockDef, KindKey, int, com.xpdustry.shard.wire.WireContext)}, handing each payload the
     * building holds, decoded, to {@code nested} in read order (null for an empty one).
     */
    public byte[] readBody(Reads in, BlockDef block, KindKey kind, int revision, com.xpdustry.shard.wire.WireContext context,
                           java.util.function.Consumer<Object> nested){
        BuildingCodec codec = codecs.get(kind);
        if(codec == null) throw new IllegalArgumentException("kind " + kind + " has no codec, so its payload body has no known length");
        if(!codec.reads(revision)) throw new IllegalArgumentException("kind " + kind + " has no codec for building revision " + revision);
        var tape = new Tape(in, context, nested);
        BuildingBase.skip(tape, block);
        codec.skip(tape, revision, block);
        return tape.bytes();
    }

    private static final class BuiltIn{
        static final BuildingCodecs CODECS = new BuildingCodecs(Registries.instances(BuildingCodec.class));
    }
}
