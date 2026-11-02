/**
 * Every v160.7 byte format (ARCHITECTURE.md §2, §6): building body codecs per kind and revision, entity sync
 * adapters, the world-stream writer, msav import and export, Rules/objectives/markers JSON and content patch output.
 * It reads immutable world projections frozen at a tick boundary, never live columns from another thread.
 */
package com.xpdustry.shard.compat.v160;
