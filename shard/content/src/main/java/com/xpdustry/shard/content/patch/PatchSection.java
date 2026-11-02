package com.xpdustry.shard.content.patch;

import java.io.*;
import java.util.*;

/**
 * The world stream's data patch section, as {@code SaveVersion.writeDataPatches} (SaveVersion.java:603) writes it:
 * the patch format version, the asset count, then per asset its type ordinal ({@code DataAssetType.patch} is 0), path,
 * an embedded flag and the embedded bytes. Patches are always embedded ({@code DataAssetType.patch.embedded}).
 */
public final class PatchSection{
    /** {@code DataPatcher.patchFormatVersion}. */
    public static final int formatVersion = 2;
    /** {@code DataAssetType.patch.ordinal()}. */
    public static final int patchType = 0;

    private PatchSection(){
    }

    /** Writes the section with {@code patches} in order; empty patches are left out. */
    public static void write(DataOutput out, List<Patch> patches) throws IOException{
        List<Patch> sent = patches.stream().filter(p -> !p.isEmpty()).toList();
        out.writeInt(formatVersion);
        out.writeInt(sent.size());
        for(Patch p : sent){
            out.writeByte(patchType);
            out.writeUTF(p.path());
            out.writeBoolean(true);
            byte[] bytes = p.bytes();
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    /** Writes the section of a world without patches. */
    public static void writeEmpty(DataOutput out) throws IOException{
        write(out, List.of());
    }
}
