/**
 * Vanilla's map generation filters ({@code mindustry.maps.filters}): {@link com.xpdustry.shard.compat.v160.filters.MapFilters}
 * reads a map's {@code genfilters} tag (or makes the default ore and scatter filters) and applies them while the map
 * loads, as {@code World.loadMap} does; each {@link com.xpdustry.shard.compat.v160.filters.GenerateFilter} is one
 * filter's options as data and its pass over a {@link com.xpdustry.shard.compat.v160.filters.FilterGrid}. The editor UI
 * is not ported.
 *
 * <p>Owner: W2.3 (map hosting).
 */
package com.xpdustry.shard.compat.v160.filters;
