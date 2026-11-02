package com.xpdustry.shard.content.patch;

import com.xpdustry.shard.common.io.*;

import java.nio.charset.*;
import java.util.*;

/**
 * One of a map's data assets, as vanilla v160.7's {@code mod.data.DataAsset} holds it once a file is read
 * ({@code SaveVersion.readDataPatches}): a content patch, a content file, or an external asset (a bundle, sprite, sound
 * or music file) that clients fetch by its SHA-256.
 */
public sealed interface DataAsset{
    /** {@code DataAssetType}, by ordinal: the byte the patch section writes. */
    enum Type{
        PATCH("patches", true), CONTENT("content", true), BUNDLE("bundles", false), IMAGE("sprites", false), SOUND("sounds", false),
        MUSIC("music", false);

        private final String folder;
        private final boolean embedded;

        Type(String folder, boolean embedded){
            this.folder = folder;
            this.embedded = embedded;
        }

        /** {@code DataAssetType.folder}. */
        public String folder(){
            return folder;
        }

        /** {@code DataAssetType.embedded}: always written whole, never by hash. */
        public boolean embedded(){
            return embedded;
        }

        /** The type with this ordinal, or null. */
        public static Type of(int ordinal){
            Type[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
        }
    }

    Type type();

    /** {@code DataAsset.path}, with {@code '\'} turned into {@code '/'} as {@code setPath} does. */
    String path();

    /**
     * {@code PatchAsset}: one patch, kept as the text the file holds (vanilla writes it back as read). Strings travel as
     * UTF-8: malformed bytes become U+FFFD on read, as {@code new String(bytes, utf8)} makes them.
     */
    record PatchAsset(String path, String text) implements DataAsset{
        public PatchAsset{
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public Type type(){
            return Type.PATCH;
        }

        public byte[] bytes(){
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** {@code ContentAsset}: new content as JSON, of {@code contentType} (vanilla's {@code ContentType} ordinal). */
    record ContentAsset(String path, int contentType, String data) implements DataAsset{
        public ContentAsset{
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(data, "data");
        }

        @Override
        public Type type(){
            return Type.CONTENT;
        }
    }

    /**
     * An asset clients fetch by hash: a bundle, image, sound or music file. A server holds its bytes when the file
     * embedded them; one written by hash alone carries none ({@code data} null), and vanilla can't serve it then. An
     * embedded asset of length 0 has neither: vanilla logs it and keeps it without a hash, and writes it back empty.
     *
     * @param hash SHA-256 of {@code data}, or the file's hash, or null for an empty embedded asset
     */
    record ExternalAsset(Type type, String path, byte[] hash, byte[] data) implements DataAsset{
        public ExternalAsset{
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(path, "path");
            if(type.embedded()) throw new IllegalArgumentException(type + " assets are always embedded");
            if(hash != null && hash.length != 32) throw new IllegalArgumentException("hash must be 32 bytes long: " + hash.length);
            hash = hash == null ? null : hash.clone();
            data = data == null ? null : data.clone();
        }

        /** An asset embedded with {@code data}: its hash is the data's. */
        public static ExternalAsset embedded(Type type, String path, byte[] data){
            return data.length == 0 ? new ExternalAsset(type, path, null, null) : new ExternalAsset(type, path, Sha256.of(data), data);
        }

        @Override
        public byte[] hash(){
            return hash == null ? null : hash.clone();
        }

        @Override
        public byte[] data(){
            return data == null ? null : data.clone();
        }

        /** Whether the server holds the bytes, so it can send them to a client that asks ({@code isCached}). */
        public boolean held(){
            return data != null;
        }

        @Override
        public boolean equals(Object o){
            return o instanceof ExternalAsset e && e.type == type && e.path.equals(path) && Arrays.equals(e.hash, hash)
                && Arrays.equals(e.data, data);
        }

        @Override
        public int hashCode(){
            return Objects.hash(type, path, Arrays.hashCode(hash));
        }

        @Override
        public String toString(){
            return "ExternalAsset[" + type + " " + path + ", " + (hash == null ? "no hash" : Sha256.hex(hash)) + ", "
                + (data == null ? "no data" : data.length + " B") + "]";
        }
    }
}
