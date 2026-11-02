package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A map's generation filters, as vanilla's {@code World.loadMap} applies them: {@code Map.filters()} reads them from the
 * map's tags ({@code Maps.readFilters}), and {@code World.FilterContext.end()} runs them, each with a fresh seed from
 * {@code Mathf.rand}, after the tiles are read and before the map ends loading. Only hosting a map does this; loading
 * a save doesn't.
 *
 * <p>Vanilla's {@code Mathf.rand} is the world's {@link World#rand()}, so a world seeded like vanilla's generator
 * draws the same filter seeds.
 */
public final class MapFilters{
    private MapFilters(){
    }

    /**
     * {@code Map.filters()}: none for maps saved before build 83 without filters; otherwise the {@code genfilters} tag
     * ({@link #read}).
     */
    public static List<GenerateFilter> of(SequencedMap<String, String> tags, ContentEpoch content){
        int build = parseInt(tags.get("build"), -1);
        String filters = tags.getOrDefault("genfilters", "");
        if(build < 83 && build != -1 && filters.isEmpty()) return List.of();
        return read(filters, content);
    }

    /**
     * {@code Maps.readFilters}: the default filters for an empty string or JSON vanilla's reader rejects, else the
     * filters the JSON lists.
     *
     * @throws MsavException for JSON that vanilla reads into something its map
     *     load then fails on, and for objects where only plain values are supported
     */
    public static List<GenerateFilter> read(String json, ContentEpoch content){
        if(json == null || json.isEmpty()) return defaults(content);
        try{
            return new FilterJson(content).read(json);
        }catch(FilterJson.Invalid e){
            return defaults(content);
        }
    }

    /**
     * The default filters: a scatter of each editor floor's decoration onto it, then an ore filter per default ore with
     * its threshold and scale, in content order.
     */
    public static List<GenerateFilter> defaults(ContentEpoch content){
        var json = new FilterJson(content);
        BlockDef air = content.block(0);
        var filters = new ArrayList<GenerateFilter>();
        for(int id = 0; id < content.blocks(); id++){
            BlockDef block = content.block(id);
            if(block instanceof FloorDef && block.fields().b("inEditor") && !block.fields().s("decoration").equals(air.name())){
                filters.add(new ScatterFilter(0.013f, block, air, json.block(block.fields().s("decoration"))));
            }
        }
        // Maps.addDefaultOres
        for(int id = 0; id < content.blocks(); id++){
            BlockDef block = content.block(id);
            if(block instanceof FloorDef floor && floor.isOverlay() && block.fields().b("oreDefault")){
                filters.add(new OreFilter(block.fields().f("oreScale"), block.fields().f("oreThreshold"), 2f, 0.3f, 0f, block, air));
            }
        }
        return filters;
    }

    /**
     * {@code FilterContext.applyFilters()} on a world that is still loading: each filter draws its seed from the
     * world's generator ({@code GenerateFilter.randomize()}), then makes its pass. {@code rules} are the file's own,
     * which are vanilla's {@code state.rules} while the map loads, and {@code ids} its content header, which still
     * maps vanilla's content ids then ({@link FilterGrid#byId}).
     */
    public static void apply(World world, List<GenerateFilter> filters, Rules rules, ContentHeader.IdMapping ids){
        if(!world.buildings().loading()) throw new IllegalStateException("filters run while the map loads");
        if(filters.isEmpty()) return;
        var grid = new FilterGrid(world, rules, ids);
        for(GenerateFilter filter : filters){
            int seed = Mathf.random(world.rand(), 999999999);
            filter.apply(grid, new GenerateInput(grid, seed));
        }
    }

    /**
     * What {@code World.FilterContext.end()} does to a map that is loading, before it ends: the map's filters
     * ({@link #of}) as {@link #apply} runs them, from the generator the map's buildings have drawn from.
     *
     * @param unknownDraws the first building whose read's draws the loader couldn't tell, or null
     * @throws MsavException when the map has filters and {@code unknownDraws} is set, so the filters would differ from
     *     vanilla's
     */
    public static void onMapLoad(World world, SequencedMap<String, String> tags, Rules rules, ContentHeader.IdMapping ids, String unknownDraws){
        List<GenerateFilter> filters = of(tags, world.content());
        if(unknownDraws != null && !filters.isEmpty()){
            throw new MsavException("the map's generation filters draw their seeds after " + unknownDraws + ", which Shard cannot count yet");
        }
        apply(world, filters, rules, ids);
    }

    /** {@code StringMap.getInt}: a malformed or missing value is the default. */
    private static int parseInt(String value, int fallback){
        if(value == null) return fallback;
        try{
            return Integer.parseInt(value);
        }catch(NumberFormatException e){
            return fallback;
        }
    }
}
