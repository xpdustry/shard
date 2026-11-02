/**
 * Block family "crafting": behaviour and state, one {@link com.xpdustry.shard.sim.building.Building} subclass per kind,
 * registered with {@code @Kind("<kind-key>")}. Crafters ({@link com.xpdustry.shard.sim.blocks.crafting.GenericCrafterBuild}
 * and its kinds: plain, attribute and heat crafters), separators and incinerators. Heat itself (heaters, conductors,
 * {@code calculateHeat}) is {@code sim.blocks.heat}'s.
 *
 * <p>Owner: WP34. The same owner holds {@code compat.v160.blocks.crafting}, the family's v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.crafting;
