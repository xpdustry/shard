package com.xpdustry.shard.sim.ai.team;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.base.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;

import java.util.*;

/**
 * Vanilla's {@code BaseRegistry} (BaseRegistry.java), loaded from the pack's base-part schematics ({@link BaseParts}):
 * the core parts by tier, the parts with no required resource, and the parts that need an item or a liquid, each
 * list sorted by tier. The ore maps ({@code ores}, {@code oreFloors}) aren't here: only the sector generators read
 * them, and Shard generates no sectors.
 *
 * <p>Immutable once made; one per content epoch, made on first use ({@link #of}).
 */
public final class BaseRegistry{
    static final KindKey CORE = KindKey.of("core-block"), ITEM_SOURCE = KindKey.of("item-source"),
        LIQUID_SOURCE = KindKey.of("liquid-source"), DRILL = KindKey.of("drill"), PUMP = KindKey.of("pump");

    /** {@code cores}, sorted by tier. */
    public final List<BasePart> cores;
    /** {@code parts}: those with no requirement, sorted by tier. */
    public final List<BasePart> parts;
    /** {@code reqParts}, by the required content; each sorted by tier. */
    private final Map<ContentRef, List<BasePart>> required;

    private BaseRegistry(List<BasePart> cores, List<BasePart> parts, Map<ContentRef, List<BasePart>> required){
        this.cores = cores;
        this.parts = parts;
        this.required = required;
    }

    /** The registry for {@code content}: {@code load()} (BaseRegistry.java:35). */
    public static BaseRegistry of(ContentEpoch content){
        var cores = new ArrayList<BasePart>();
        var parts = new ArrayList<BasePart>();
        var required = new LinkedHashMap<ContentRef, List<BasePart>>();
        for(Schematic schematic : BaseParts.read(content)){
            BlockDef core = null;
            ContentRef need = null;
            float sumX = 0f, sumY = 0f;
            int drills = 0;
            for(Schematic.Tile tile : schematic.tiles()){
                BlockDef block = tile.block();
                // keep track of core type
                if(block.isA(CORE)) core = block;
                // the required resource, from the item source's config; multiple sources are not allowed
                if(block.isA(ITEM_SOURCE) || block.isA(LIQUID_SOURCE)){
                    ContentRef config = (ContentRef)config(tile.config(), content);
                    if(config != null) need = config;
                }
                // calculate averages
                if(block.isA(DRILL) || block.isA(PUMP)){
                    float offset = Footprint.worldOffset(block.size());
                    sumX += tile.x() * 8 + offset;
                    sumY += tile.y() * 8 + offset;
                    drills++;
                }
            }
            var tiles = new ArrayList<Stile>(schematic.tiles().size());
            float tier = 0f;
            for(Schematic.Tile tile : schematic.tiles()){
                if(tile.block().buildVisibility().equals("visibility:sandboxOnly")) continue;
                tiles.add(new Stile(tile.block(), tile.x(), tile.y(), config(tile.config(), content), tile.rotation()));
            }
            // tiles.sumf(...), in order
            for(Stile tile : tiles){
                tier += Mathf.pow(tile.block.buildTime() / tile.block.fields().f("buildCostMultiplier"), 1.4f);
            }
            int centerX, centerY;
            if(drills > 0){
                // Tmp.v1.scl(1f / drills).scl(1f / tilesize)
                float scale = 1f / drills;
                float cx = sumX * scale, cy = sumY * scale;
                centerX = (int)(cx * (1f / 8f));
                centerY = (int)(cy * (1f / 8f));
            }else{
                centerX = schematic.width() / 2;
                centerY = schematic.height() / 2;
            }
            var part = new BasePart(schematic.name(), schematic.width(), schematic.height(), List.copyOf(tiles), centerX, centerY, need, core, tier);
            if(core != null){
                cores.add(part);
            }else if(need == null){
                parts.add(part);
            }
            if(need != null && core == null) required.computeIfAbsent(need, k -> new ArrayList<>()).add(part);
        }
        // Seq.sort is stable, as List.sort is
        Comparator<BasePart> byTier = (a, b) -> Float.compare(a.tier, b.tier);
        cores.sort(byTier);
        parts.sort(byTier);
        var frozen = new LinkedHashMap<ContentRef, List<BasePart>>();
        for(var entry : required.entrySet()){
            entry.getValue().sort(byTier);
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new BaseRegistry(List.copyOf(cores), List.copyOf(parts), Collections.unmodifiableMap(frozen));
    }

    /** {@code forResource(item)}: the parts that need it, or none. */
    public List<BasePart> forResource(ContentRef content){
        return required.getOrDefault(content, List.of());
    }

    /** Every required resource with parts, in the order their first part was read. */
    public Set<ContentRef> resources(){
        return required.keySet();
    }

    /**
     * A schematic config as a build plan holds one: content becomes a {@link ContentRef} (null for an id the content
     * lacks, as {@code getByID} gives), or a {@link ContentRef.Foreign} for a type no epoch holds.
     */
    static Object config(Object config, ContentEpoch content){
        if(!(config instanceof Schematic.ContentValue value)) return config;
        for(ContentType type : ContentType.values()){
            if(type.vanillaOrdinal() != value.vanillaType()) continue;
            return value.id() >= 0 && value.id() < content.count(type) ? new ContentRef(type, value.id()) : null;
        }
        return new ContentRef.Foreign(value.vanillaType(), value.id());
    }

    /**
     * {@code BasePart}: a schematic, without its sandbox-only tiles, with the offset its drills average to (or its
     * middle), what it needs and its total build cost.
     */
    public record BasePart(String name, int width, int height, List<Stile> tiles, int centerX, int centerY, ContentRef required,
                           BlockDef core, float tier){
    }

    /** {@code Schematic.Stile}: a block at (x, y) of its schematic, with its config and rotation. */
    public record Stile(BlockDef block, int x, int y, Object config, int rotation){
    }
}
