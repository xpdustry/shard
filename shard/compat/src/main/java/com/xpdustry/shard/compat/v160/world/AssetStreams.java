package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.content.patch.DataAsset.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * The streams a join of a world with external assets (a map's sprites, sounds…; {@link MapState#assets}) takes before
 * the world, as vanilla's {@code NetServer.sendWorldAndAssets} sends them: an {@code AssetRequirementStream} with every
 * asset's hash, to which the client answers {@code requestAssets} with the indices it lacks, then an
 * {@code AssetStream} with those, after which it asks for the world ({@code requestWorld}). A world without external
 * assets sends its world at once.
 */
public final class AssetStreams{
    /** {@code Streamable} packet ids ({@code Net.packetIdAssetRequirementStream}, {@code packetIdAssetStream}). */
    public static final int REQUIREMENT_STREAM = 4, ASSET_STREAM = 5;

    private AssetStreams(){
    }

    /**
     * {@code sendAssetRequirements}: {@code NetworkIO.writeRequiredAssets} through a deflater, the count then each
     * external asset's 32-byte hash, in {@code getAllExternalAssets} order.
     *
     * @throws IllegalStateException for an asset without a hash, as vanilla's writer throws
     */
    public static byte[] requirements(DataAssets assets){
        var bytes = new ByteArrayOutputStream();
        try(var out = new DataOutputStream(new DeflaterOutputStream(bytes))){
            List<ExternalAsset> externals = assets.externals();
            out.writeInt(externals.size());
            for(ExternalAsset asset : externals){
                byte[] hash = asset.hash();
                if(hash == null) throw new IllegalStateException("Invalid asset (missing hash): " + asset.path());
                out.write(hash);
            }
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * The assets a client's {@code requestAssets(ids)} gets, in request order: ids out of range and repeats are
     * skipped (NetServer.java:976). Empty means the client has them all, and vanilla sends the world instead.
     */
    public static List<ExternalAsset> requested(DataAssets assets, short[] ids){
        List<ExternalAsset> all = assets.externals();
        var seen = new BitSet(all.size());
        var out = new ArrayList<ExternalAsset>();
        for(short id : ids){
            if(id >= all.size() || id < 0 || seen.get(id)) continue;
            seen.set(id);
            out.add(all.get(id));
        }
        return out;
    }

    /**
     * {@code NetworkIO.writeAssets}, not deflated: the count, then each asset's length and bytes.
     *
     * @throws IllegalStateException for an asset whose bytes the server doesn't hold, where vanilla's
     *     {@code getCacheFileNoNull} throws
     */
    public static byte[] assets(List<ExternalAsset> assets){
        var bytes = new ByteArrayOutputStream();
        try(var out = new DataOutputStream(bytes)){
            out.writeInt(assets.size());
            for(ExternalAsset asset : assets){
                if(!asset.held()) throw new IllegalStateException("Cache file for asset " + asset.path() + " not found!");
                byte[] data = asset.data();
                out.writeInt(data.length);
                out.write(data);
            }
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
