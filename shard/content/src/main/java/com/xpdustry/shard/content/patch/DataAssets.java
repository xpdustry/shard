package com.xpdustry.shard.content.patch;

import com.xpdustry.shard.content.patch.DataAsset.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * A world's data assets in the order vanilla's {@code DataManager} holds them once loaded: by type ordinal, then in
 * file order ({@code rebuildOrderedAssets}). That is the order {@code writeDataPatches} writes them in, in the world
 * stream and in saves, and the order of {@code getAllExternalAssets}, whose indices a client's {@code requestAssets}
 * names.
 */
public record DataAssets(List<DataAsset> all){
    /** No assets: what a map without patches loads, and what {@code DataManager.unload} leaves. */
    public static final DataAssets NONE = new DataAssets(List.of());

    public DataAssets{
        all = List.copyOf(all);
    }

    /** {@code assets} as {@code DataManager.load} orders them: grouped by type, in type order, stable within one. */
    public static DataAssets loaded(List<? extends DataAsset> assets){
        var out = new ArrayList<DataAsset>(assets);
        out.sort(Comparator.comparingInt(a -> a.type().ordinal()));
        return new DataAssets(out);
    }

    public boolean isEmpty(){
        return all.isEmpty();
    }

    /** The patches, in order. */
    public List<PatchAsset> patches(){
        var out = new ArrayList<PatchAsset>();
        for(DataAsset a : all) if(a instanceof PatchAsset p) out.add(p);
        return out;
    }

    /** The content assets, in order. */
    public List<ContentAsset> contents(){
        var out = new ArrayList<ContentAsset>();
        for(DataAsset a : all) if(a instanceof ContentAsset c) out.add(c);
        return out;
    }

    /** {@code getAllExternalAssets}: what a client fetches by hash, in the order its requests index. */
    public List<ExternalAsset> externals(){
        var out = new ArrayList<ExternalAsset>();
        for(DataAsset a : all) if(a instanceof ExternalAsset e) out.add(e);
        return out;
    }

    /** {@code hasExternalAssets}: whether a join must offer the client assets before the world. */
    public boolean hasExternal(){
        for(DataAsset a : all) if(a instanceof ExternalAsset) return true;
        return false;
    }

    /**
     * These assets with only the patches of {@code kept}, which {@code DataPatcher.apply} kept in order: the others a
     * {@code requiredPlanets} filter took out of the patch list, and vanilla's {@code state.data} no longer holds them.
     */
    public DataAssets retaining(List<PatchAsset> kept){
        var out = new ArrayList<DataAsset>(all.size());
        int next = 0;
        for(DataAsset a : all){
            if(a instanceof PatchAsset p){
                if(next < kept.size() && kept.get(next).equals(p)){
                    next++;
                    out.add(a);
                }
                continue;
            }
            out.add(a);
        }
        if(next != kept.size()) throw new IllegalArgumentException("kept patches that aren't these assets' in order");
        return new DataAssets(out);
    }

    // reading

    /**
     * The assets of a file's patch region: {@code version} 13 and later in {@code SaveVersion.readDataPatches}'s
     * layout, 12 in {@code Save12}'s (patches, then images with their size), 11 in {@code Save11}'s (patches only),
     * in file order. Patches in versions 11 and 12 have no path: vanilla gives each {@code "patch-" + randomUUID +
     * ".json"} ({@code new PatchAsset(String)}); Shard names it by a hash of its bytes instead, so a world written
     * twice writes it alike.
     *
     * @throws IllegalArgumentException where vanilla's read throws, or the region is cut short
     */
    public static List<DataAsset> read(byte[] region, int version){
        var in = new DataInputStream(new ByteArrayInputStream(region));
        try{
            List<DataAsset> out = switch(version){
                case 11 -> read11(in);
                case 12 -> read12(in);
                default -> read13(in);
            };
            if(in.available() > 0) throw new IllegalArgumentException(in.available() + " bytes of the patch region left unread");
            return List.copyOf(out);
        }catch(EOFException e){
            throw new IllegalArgumentException("the patch region ends inside an asset", e);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    private static List<DataAsset> read11(DataInputStream in) throws IOException{
        var out = new ArrayList<DataAsset>();
        int amount = in.readUnsignedByte();
        for(int i = 0; i < amount; i++) out.add(unnamedPatch(bytes(in, in.readInt())));
        return out;
    }

    private static List<DataAsset> read12(DataInputStream in) throws IOException{
        var out = new ArrayList<DataAsset>();
        in.readInt(); // version, ignored
        int patches = in.readInt();
        for(int i = 0; i < patches; i++) out.add(unnamedPatch(bytes(in, in.readInt())));
        int images = in.readInt();
        for(int i = 0; i < images; i++){
            String name = in.readUTF();
            in.readShort(); // width and height, ignored
            in.readShort();
            byte[] data = bytes(in, in.readInt());
            // new ImageAsset(name, assetCache.add(bytes)): hashed even when empty
            out.add(new ExternalAsset(Type.IMAGE, path(name), com.xpdustry.shard.common.io.Sha256.of(data), data));
        }
        return out;
    }

    private static List<DataAsset> read13(DataInputStream in) throws IOException{
        in.readInt(); // DataPatcher.patchFormatVersion, ignored
        int total = in.readInt();
        var out = new ArrayList<DataAsset>(Math.max(0, Math.min(total, 1024)));
        for(int i = 0; i < total; i++){
            int typeId = in.readByte();
            Type type = Type.of(typeId);
            if(type == null) throw new IllegalArgumentException("invalid asset type id " + typeId);
            String path = path(in.readUTF());
            boolean embedded = in.readBoolean();
            out.add(switch(type){
                // PatchAsset.read and ContentAsset.read, whatever the flag says: a patch is always embedded
                case PATCH -> embedded ? new PatchAsset(path, text(bytes(in, in.readInt()))) : hashOnly(type, path, in);
                case CONTENT -> {
                    if(!embedded) yield hashOnly(type, path, in);
                    int contentType = in.readShort();
                    yield new ContentAsset(path, contentType, text(bytes(in, in.readInt())));
                }
                default -> embedded ? ExternalAsset.embedded(type, path, bytes(in, in.readInt())) : hashOnly(type, path, in);
            });
        }
        return out;
    }

    /** A patch or content asset stored by hash: vanilla reads the hash and loads it from its cache, empty if missing. */
    private static DataAsset hashOnly(Type type, String path, DataInputStream in) throws IOException{
        byte[] hash = bytes(in, 32);
        if(type.embedded()) throw new IllegalArgumentException(type + " asset " + path + " is stored by hash, which only an asset cache could resolve");
        return new ExternalAsset(type, path, hash, null);
    }

    /** A v11/v12 patch, named {@code patch-<uuid>.json} as vanilla names it, the UUID's digits from the bytes' SHA-256 (Shard's choice; vanilla's are random). */
    private static PatchAsset unnamedPatch(byte[] bytes){
        String hex = com.xpdustry.shard.common.io.Sha256.hex(com.xpdustry.shard.common.io.Sha256.of(bytes));
        String uuid = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
            + hex.substring(20, 32);
        return new PatchAsset("patch-" + uuid + ".json", text(bytes));
    }

    private static byte[] bytes(DataInputStream in, int length) throws IOException{
        if(length < 0 || length > in.available()) throw new IllegalArgumentException("an asset claims " + length + " bytes, but " + in.available() + " remain");
        byte[] out = new byte[length];
        in.readFully(out);
        return out;
    }

    private static String text(byte[] bytes){
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String path(String path){
        return path.replace('\\', '/');
    }

    // writing

    /** {@link #write(boolean)} as a world stream and a server's saves write it: external assets by hash. */
    public byte[] write(){
        return write(false);
    }

    /**
     * {@code SaveVersion.writeDataPatches(stream, forceEmbed)}: the format version, the count, then per asset its type,
     * path, whether it is embedded, and its bytes or its hash. An asset is embedded when its type always is, when
     * {@code forceEmbed} (a map export), or when it has no hash; an external one embeds the bytes the server holds, or
     * none ({@code DataAsset.write} with no cache file writes a length of 0).
     */
    public byte[] write(boolean forceEmbed){
        var bytes = new ByteArrayOutputStream();
        try(var out = new DataOutputStream(bytes)){
            out.writeInt(PatchSection.formatVersion);
            out.writeInt(all.size());
            for(DataAsset asset : all){
                byte[] hash = asset instanceof ExternalAsset e ? e.hash() : null;
                boolean embed = forceEmbed || asset.type().embedded() || hash == null;
                out.writeByte(asset.type().ordinal());
                out.writeUTF(asset.path());
                out.writeBoolean(embed);
                if(!embed){
                    out.write(hash);
                    continue;
                }
                switch(asset){
                    case PatchAsset p -> {
                        byte[] text = p.bytes();
                        out.writeInt(text.length);
                        out.write(text);
                    }
                    case ContentAsset c -> {
                        out.writeShort(c.contentType());
                        byte[] data = c.data().getBytes(StandardCharsets.UTF_8);
                        out.writeInt(data.length);
                        out.write(data);
                    }
                    case ExternalAsset e -> {
                        byte[] data = e.held() ? e.data() : new byte[0];
                        out.writeInt(data.length);
                        out.write(data);
                    }
                }
            }
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
