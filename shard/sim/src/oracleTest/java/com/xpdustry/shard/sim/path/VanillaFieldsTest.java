package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The vanilla gate (ARCHITECTURE.md §9): on each of the 18 default maps, hosted by the oracle, Shard's packed tiles
 * equal vanilla's {@code packTile}, and for the wave team and the default team, every field vanilla computes (ground,
 * legs, naval, hover) has the same targets and the same distance on every tile as vanilla's {@code Pathfinder} after
 * a refresh. {@code next} picks the tile vanilla's {@code getTargetTile} picks, on every tile.
 *
 * <p>Vanilla's very first build of a field differs from its later ones: {@code registerPath} doesn't mark the targets
 * as searched, so the search overwrites their weight of 0. The test checks that this is the only difference; Shard
 * keeps its targets at 0.
 */
class VanillaFieldsTest{
    static final List<String> MAPS = List.of("archipelago", "caldera", "debrisField", "domain", "fork", "fortress", "glacier",
        "islands", "labyrinth", "maze", "moltenLake", "mudFlats", "passage", "shattered", "tendrils", "triad", "veins", "wasteland");

    @Test
    void theEpochTableIsVanillasContent(){
        Vanilla.boot();
        PathBlocks live = VanillaPaths.liveBlocks(), epoch = PathWorlds.BLOCKS;
        assertEquals(live.size(), epoch.size());
        for(int id = 0; id < live.size(); id++){
            assertEquals(Integer.toBinaryString(live.flags(id)), Integer.toBinaryString(epoch.flags(id)), "flags of " + content.block(id).name);
            assertEquals(live.turretRange(id), epoch.turretRange(id), "turret range of " + content.block(id).name);
        }
    }

    static List<String> maps(){
        return MAPS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void fieldsEqualVanilla(String map){
        Vanilla.host(map);
        int[] vanilla = VanillaPaths.repack();
        World shard = VanillaPaths.mirror();
        PathService paths = PathWorlds.paths(shard);
        paths.rules(VanillaPaths.rules());
        paths.invalidateAll();
        shard.tick();
        int width = world.width(), height = world.height();
        for(int pos = 0; pos < vanilla.length; pos++){
            int mine = paths.packed(pos) & PathTile.VANILLA_BITS;
            if(mine != vanilla[pos]){
                fail(map + ": packed tile " + (pos % width) + "," + (pos / width) + " is " + Integer.toBinaryString(mine)
                    + ", vanilla " + Integer.toBinaryString(vanilla[pos]) + " (" + world.tiles.geti(pos) + ")");
            }
        }
        var report = new StringBuilder("| " + map + " | " + width + "x" + height + " |");
        int teams = 0;
        for(int team : new int[]{state.rules.waveTeam.id, state.rules.defaultTeam.id}){
            for(var entry : VanillaPaths.COSTS.entrySet()){
                PathCost cls = entry.getKey();
                VanillaPaths.Built built = VanillaPaths.build(team, entry.getValue());
                int handle = paths.field(team, cls);
                shard.tick();
                String where = map + " team " + team + " " + cls;
                Flowfield field = paths.get(handle);
                assertArrayEquals(built.targets(), field.targets.toArray(), where + ": targets");
                int reached = 0;
                for(int pos = 0; pos < vanilla.length; pos++){
                    int expected = built.refreshed()[pos], actual = paths.distance(handle, pos);
                    if(expected != actual) fail(where + ": tile " + (pos % width) + "," + (pos / width) + " is " + actual + ", vanilla " + expected);
                    if(expected >= 0) reached++;
                    if(built.first()[pos] != expected && Arrays.binarySearch(built.targets(), pos) < 0){
                        fail(where + ": vanilla's first build differs off the targets at " + (pos % width) + "," + (pos / width));
                    }
                }
                for(int pos = 0; pos < vanilla.length; pos++){
                    Tile tile = world.tiles.geti(pos), next = pathfinder.getTargetTile(tile, built.field());
                    int expected = next.x + next.y * width, actual = paths.next(handle, pos);
                    if(expected != actual) fail(where + ": from " + tile.x + "," + tile.y + " vanilla steps to " + next.x + "," + next.y
                        + ", Shard to " + (actual % width) + "," + (actual / width));
                }
                report.append(' ').append(reached).append(" |");
            }
            teams++;
        }
        assertEquals(2, teams);
        System.out.println(report);
    }
}
