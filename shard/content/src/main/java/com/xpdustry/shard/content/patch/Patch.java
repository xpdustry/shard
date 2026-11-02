package com.xpdustry.shard.content.patch;

import com.xpdustry.shard.common.io.*;

import java.nio.charset.*;
import java.util.*;

/**
 * A vanilla content patch (v160 {@code PatchAsset}): one JSON object whose keys are dotted paths
 * ({@code "block.duo.health": 300}). Applied by a vanilla client to its own content, it gives the content of the
 * world {@link #worldHash()} pins, derived fields included.
 *
 * @param path the asset path the world stream names it by
 * @param entries the paths it sets, in order
 */
public record Patch(String baseHash, String worldHash, String path, String json, List<String> entries){
    public Patch{
        entries = List.copyOf(entries);
    }

    /** Whether the world's content is the base's: a world with an empty patch sends none. */
    public boolean isEmpty(){
        return entries.isEmpty();
    }

    /** The JSON as UTF-8, as the world stream embeds it. */
    public byte[] bytes(){
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** SHA-256 of {@link #bytes()}: vanilla's asset cache key. */
    public byte[] sha256(){
        return Sha256.of(bytes());
    }
}
