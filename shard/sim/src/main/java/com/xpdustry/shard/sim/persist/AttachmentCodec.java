package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.entity.*;

/**
 * Stores the values of one authoritative attachment key, which checkpoints keep by the key's name, never its id
 * (ARCHITECTURE.md §3 "Attachments"). Register it with {@code @Register} next to the key. Capturing an entity with an
 * authoritative attachment that has no codec fails; derived attachments are skipped and rebuilt by a
 * {@link RebuildHook}.
 *
 * @param <T> the value type
 */
@Registry
public interface AttachmentCodec<T>{
    AttachmentKey<T> key();

    void write(T value, CheckpointOut out);

    T read(CheckpointIn in);
}
