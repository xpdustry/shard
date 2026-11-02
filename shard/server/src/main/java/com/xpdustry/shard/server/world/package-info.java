/**
 * The world manager: world ids, the lifecycle {@code RUNNING -> QUIESCING -> HIBERNATED -> WAKING -> RUNNING} plus
 * {@code FAILED}, single-flight wake, frozen time in empty worlds, map rotation.
 *
 * <p>Owners: WP48 (rotation), the persistence work package (hibernation).
 */
package com.xpdustry.shard.server.world;
