package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.rules.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.blocks.units.UnitBlockRun.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The unit assembler's and its module's placement rules ({@code canPlaceOn}, and {@code Build.validPlace} around them)
 * against vanilla's, on every tile of a map and in every rotation: a built tank assembler, an assembler of another team,
 * and a mech assembler under construction ({@code ConstructBuild.current}), whose areas a new assembler may not overlap
 * (grown by 0.1, so touching edges count), and against whose areas a module must sit, facing the centre.
 */
class AssemblerPlacementOracleTest{
    @Test
    void assemblersAndModulesPlaceAsVanillas(){
        int size = 64;
        var run = new UnitBlockRun(size, size, pos -> "stone", Map.of());
        state.rules.planet = mindustry.content.Planets.erekir;
        run.shard.part(WorldRules.class).rules.planet = 1;
        run.place("tank-assembler", 10, 12, 0);
        // another team's assembler: it doesn't count for sharded
        world.tile(30, 50).setBlock(content.block("ship-assembler"), Team.crux, 3);
        run.shard.buildings().setBlock(30, 50, EPOCH.block("ship-assembler"), Team.crux.id, 3, com.xpdustry.shard.sim.world.Cause.SERVER);
        // a mech assembler under construction, facing down
        mindustry.world.Build.beginPlace(null, content.block("mech-assembler"), Team.sharded, 46, 40, 3, null);
        Build.beginPlace(run.shard, null, EPOCH.block("mech-assembler"), Team.sharded.id, 46, 40, 3, null);
        com.xpdustry.shard.oracle.Vanilla.runPosted();
        assertTrue(world.build(46, 40) instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild, "vanilla's site");
        assertTrue(run.shard.buildings().at(46, 40) instanceof ConstructBuild, "Shard's site");

        var counts = new TreeMap<String, Integer>();
        var differences = new ArrayList<String>();
        for(String name : List.of("tank-assembler", "basic-assembler-module")){
            var vanillaBlock = content.block(name);
            var block = EPOCH.block(name);
            PlaceInfo info = PlaceInfo.of(block, run.shard.content());
            for(int y = 0; y < size; y++){
                for(int x = 0; x < size; x++){
                    for(int rotation = 0; rotation < 4; rotation++){
                        boolean expected = vanillaBlock.canPlaceOn(world.tile(x, y), Team.sharded, rotation);
                        boolean actual = Build.canPlaceOn(run.shard, info, x, y, Team.sharded.id, rotation);
                        if(expected != actual) differences.add(name + " canPlaceOn at " + x + "," + y + " rot " + rotation + ": vanilla " + expected);
                        boolean expectedValid = mindustry.world.Build.validPlace(vanillaBlock, Team.sharded, x, y, rotation);
                        boolean actualValid = Build.validPlace(run.shard, block, Team.sharded.id, x, y, rotation);
                        if(expectedValid != actualValid) differences.add(name + " validPlace at " + x + "," + y + " rot " + rotation + ": vanilla " + expectedValid);
                        counts.merge(name + " " + expected, 1, Integer::sum);
                        if(expectedValid) counts.merge(name + " valid", 1, Integer::sum);
                    }
                }
            }
        }
        assertEquals(List.of(), differences.size() > 20 ? differences.subList(0, 20) : differences, differences.size() + " differences");
        assertTrue(counts.getOrDefault("tank-assembler false", 0) > 1000 && counts.getOrDefault("tank-assembler valid", 0) > 1000, counts.toString());
        assertTrue(counts.getOrDefault("basic-assembler-module true", 0) > 40 && counts.getOrDefault("basic-assembler-module valid", 0) > 20, counts.toString());
    }
}
