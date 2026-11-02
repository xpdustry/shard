package com.xpdustry.shard.compat.v160.command;

import com.xpdustry.shard.content.base.BaseParts;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.ai.team.*;
import com.xpdustry.shard.sim.building.ContentRef;
import mindustry.ai.BaseRegistry.*;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shard's {@link BaseRegistry} against vanilla's {@code Vars.bases}, loaded at boot from the same bundled files: the
 * same cores, parts and required-resource parts, in the same order, each with the same tier (to the bit), centre,
 * requirement, core and tiles (block, position, rotation, config); and each part turned by {@code Schematics.rotate}
 * at -2 to 2 quarter turns as {@link SchematicRotation} turns it, processors' links included.
 */
class BaseRegistryOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void thePackHoldsVanillasFiles(){
        List<String> names = BaseParts.names();
        assertEquals(210, names.size());
        for(String name : names){
            assertArrayEquals(arc.Core.files.internal("baseparts/" + name).readBytes(), BaseParts.bytes(name), name);
        }
    }

    @Test
    void theRegistryIsVanillas(){
        BaseRegistry ours = BaseRegistry.of(CommandRun.EPOCH);
        sameParts(bases.cores, ours.cores, "cores");
        sameParts(bases.parts, ours.parts, "parts");
        var theirs = new TreeMap<String, arc.struct.Seq<BasePart>>();
        for(var entry : bases.reqParts) theirs.put(entry.key.getContentType().name() + "/" + ((mindustry.ctype.MappableContent)entry.key).name, entry.value);
        var mine = new TreeMap<String, List<BaseRegistry.BasePart>>();
        for(ContentRef ref : ours.resources()) mine.put(ref.type().yamlName() + "/" + CommandRun.EPOCH.get(ref.type(), ref.id()).name(), ours.forResource(ref));
        assertEquals(theirs.keySet(), mine.keySet(), "required resources");
        for(String key : theirs.keySet()) sameParts(theirs.get(key), mine.get(key), "parts for " + key);
        int total = bases.cores.size + bases.parts.size;
        for(var list : theirs.values()) total += list.size;
        assertTrue(total >= 200, "every part was compared: " + total);
    }

    @Test
    void rotationsAreVanillas(){
        BaseRegistry ours = BaseRegistry.of(CommandRun.EPOCH);
        int compared = 0, processors = 0;
        var theirs = new ArrayList<BasePart>();
        var mine = new ArrayList<BaseRegistry.BasePart>();
        theirs.addAll(Arrays.asList(bases.parts.toArray(BasePart.class)));
        mine.addAll(ours.parts);
        theirs.addAll(Arrays.asList(bases.cores.toArray(BasePart.class)));
        mine.addAll(ours.cores);
        for(ContentRef ref : ours.resources()) mine.addAll(ours.forResource(ref));
        for(var entry : bases.reqParts) theirs.addAll(Arrays.asList(entry.value.toArray(BasePart.class)));
        // reqParts' iteration order differs; match parts by their tiles
        var byTiles = new HashMap<String, BasePart>();
        for(BasePart part : theirs) byTiles.put(tiles(part.schematic.tiles), part);
        for(BaseRegistry.BasePart part : mine){
            BasePart vanilla = byTiles.get(ourTiles(part.tiles()));
            assertNotNull(vanilla, "a vanilla part for " + part.name());
            for(int times = -2; times <= 2; times++){
                Schematic rotated = Schematics.rotate(vanilla.schematic, times);
                var result = SchematicRotation.rotate(part, times);
                String at = part.name() + " turned " + times;
                assertEquals(rotated.width, result.width(), at);
                assertEquals(rotated.height, result.height(), at);
                CommandRun.sameLines(Arrays.asList(tiles(rotated.tiles).split(";")), Arrays.asList(ourTiles(result.tiles()).split(";")), at);
                compared++;
            }
            for(var tile : part.tiles()) if(tile.block().name().endsWith("processor")) processors++;
        }
        assertTrue(compared >= 1000, "rotations compared: " + compared);
        assertTrue(processors > 0, "processors' links were turned");
    }

    private static void sameParts(arc.struct.Seq<BasePart> theirs, List<BaseRegistry.BasePart> ours, String what){
        assertEquals(theirs.size, ours.size(), what);
        for(int i = 0; i < theirs.size; i++){
            BasePart a = theirs.get(i);
            BaseRegistry.BasePart b = ours.get(i);
            String at = what + " #" + i + " (" + b.name() + ")";
            CommandRun.sameLines(Arrays.asList(tiles(a.schematic.tiles).split(";")), Arrays.asList(ourTiles(b.tiles()).split(";")), at + " tiles");
            assertEquals(Float.floatToIntBits(a.tier), Float.floatToIntBits(b.tier()), at + " tier");
            assertEquals(a.centerX, b.centerX(), at + " centerX");
            assertEquals(a.centerY, b.centerY(), at + " centerY");
            assertEquals(a.required == null ? "-" : ((mindustry.ctype.MappableContent)a.required).name,
                b.required() == null ? "-" : CommandRun.EPOCH.get(b.required().type(), b.required().id()).name(), at + " required");
            assertEquals(a.core == null ? "-" : a.core.name, b.core() == null ? "-" : b.core().name(), at + " core");
            assertEquals(a.schematic.width, b.width(), at + " width");
            assertEquals(a.schematic.height, b.height(), at + " height");
        }
    }

    private static String tiles(arc.struct.Seq<Schematic.Stile> tiles){
        var out = new StringBuilder();
        for(Schematic.Stile t : tiles){
            out.append(t.block.name).append('@').append(t.x).append(',').append(t.y).append(" r").append(t.rotation).append(' ')
                .append(TeamAiRun.config(t.config)).append(';');
        }
        return out.toString();
    }

    private static String ourTiles(List<BaseRegistry.Stile> tiles){
        var out = new StringBuilder();
        for(BaseRegistry.Stile t : tiles){
            out.append(t.block().name()).append('@').append(t.x()).append(',').append(t.y()).append(" r").append(t.rotation()).append(' ')
                .append(TeamAiRun.config(t.config())).append(';');
        }
        return out.toString();
    }
}
