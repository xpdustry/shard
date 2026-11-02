package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MapFiltersTest{
    static SequencedMap<String, String> tags(String... pairs){
        var tags = new LinkedHashMap<String, String>();
        for(int i = 0; i < pairs.length; i += 2) tags.put(pairs[i], pairs[i + 1]);
        return tags;
    }

    @Test
    void oldMapsWithoutFiltersHaveNone(){
        assertEquals(List.of(), MapFilters.of(tags("build", "82"), Worlds.EPOCH));
        assertEquals(MapFilters.defaults(Worlds.EPOCH), MapFilters.of(tags("build", "83"), Worlds.EPOCH));
        assertEquals(MapFilters.defaults(Worlds.EPOCH), MapFilters.of(tags(), Worlds.EPOCH), "no build tag is -1");
        assertEquals(1, MapFilters.of(tags("build", "40", "genfilters", "[{class:distort}]"), Worlds.EPOCH).size());
    }

    @Test
    void defaultsScatterDecorationsThenAddOres(){
        List<GenerateFilter> defaults = MapFilters.defaults(Worlds.EPOCH);
        int ores = (int)defaults.stream().filter(OreFilter.class::isInstance).count();
        assertTrue(ores > 0);
        assertTrue(defaults.subList(0, defaults.size() - ores).stream().allMatch(ScatterFilter.class::isInstance));
        var copper = (OreFilter)defaults.stream().filter(f -> f instanceof OreFilter o && o.ore().name().equals("ore-copper")).findFirst().orElseThrow();
        assertEquals("air", copper.target().name());
    }

    @Test
    void anEmptyObjectIsNoFiltersAndBadJsonTheDefaults(){
        assertEquals(List.of(), MapFilters.read("{}", Worlds.EPOCH));
        assertEquals(MapFilters.defaults(Worlds.EPOCH), MapFilters.read("not json", Worlds.EPOCH));
        assertEquals(MapFilters.defaults(Worlds.EPOCH), MapFilters.read("[{class:nope}]", Worlds.EPOCH));
        assertThrows(MsavException.class, () -> MapFilters.read("[{scl:2}]", Worlds.EPOCH));
    }
}
