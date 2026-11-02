package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.Teams;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.game.Teams.TeamData;
import mindustry.world.blocks.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: hosting each default map ({@code host <map> survival}: {@code world.loadMap}, the mode's rules,
 * {@code logic.play()}) leaves Shard ({@link MapLoader#loadMap}, then {@link MapLoader#play}) where it leaves vanilla:
 * the wave countdown and tick, the weather cooldowns, the active teams in order, each team's cores in registration
 * order and its enemies, every core's items, capacity and health, every storage block's link and items, the next
 * entity id and the generator.
 */
class PlayOracleTest{
    static List<String> maps(){
        return BundledMaps.DEFAULT;
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void hostingEqualsVanillasLogicPlay(String name){
        Vanilla.host(name);
        List<String> expected = vanilla();

        SaveFile save = READER.read(BundledMaps.read(name));
        Rules rules = LOADER.hostRules(save, Gamemode.SURVIVAL);
        MapLoader.Loaded loaded = LOADER.loadMap(save, Worlds.config(0), rules);
        MapLoader.play(loaded);
        List<String> actual = shard(loaded);

        assertEquals(String.join("\n", expected), String.join("\n", actual), name);
    }

    static List<String> vanilla(){
        var out = new ArrayList<String>();
        out.add("wavetime " + state.wavetime + " tick " + state.tick);
        var weather = new StringJoiner(",");
        for(var entry : state.rules.weather) weather.add(Float.toString(entry.cooldown));
        out.add("weather " + weather);
        var active = new IntSeq();
        for(TeamData data : state.teams.getActive()) active.add(data.team.id);
        out.add("active " + active);
        for(mindustry.game.Team team : mindustry.game.Team.all){
            TeamData data = state.teams.getOrNull(team);
            if(data == null || (data.cores.isEmpty() && data.coreEnemies.length == 0)) continue;
            var line = new StringBuilder("team " + team.id + " enemies");
            for(mindustry.game.Team enemy : data.coreEnemies) line.append(' ').append(enemy.id);
            out.add(line.toString());
            for(CoreBlock.CoreBuild core : data.cores){
                out.add("  core " + core.block.name + " " + core.tile.x + "," + core.tile.y + " capacity " + core.storageCapacity
                    + " health " + core.health + " items " + items(core.items));
            }
        }
        for(var tile : world.tiles){
            if(tile.build instanceof StorageBlock.StorageBuild storage && tile.isCenter()){
                out.add("storage " + tile.block().name + " " + tile.x + "," + tile.y + " linked "
                    + (storage.linkedCore == null ? "-" : storage.linkedCore.tile.x + "," + storage.linkedCore.tile.y) + " items " + items(storage.items));
            }
        }
        out.add("next id " + CoreIds.lastId());
        out.add("rand " + arc.math.Mathf.rand.seed0 + " " + arc.math.Mathf.rand.seed1);
        return out;
    }

    static List<String> shard(MapLoader.Loaded loaded){
        World world = loaded.world();
        var tiles = world.tiles();
        var out = new ArrayList<String>();
        out.add("wavetime " + loaded.state().wavetime + " tick " + loaded.state().tick);
        var weather = new StringJoiner(",");
        for(var entry : world.part(WorldRules.class).rules.weather) weather.add(Float.toString(entry.cooldown));
        out.add("weather " + weather);
        Teams teams = Teams.of(world);
        out.add("active " + teams.getActive(world));
        for(int team = 0; team < com.xpdustry.shard.sim.team.Team.COUNT; team++){
            TeamDataView data = new TeamDataView(teams.get(team));
            if(data.cores().isEmpty() && data.enemies().length == 0) continue;
            var line = new StringBuilder("team " + team + " enemies");
            for(int enemy : data.enemies()) line.append(' ').append(enemy);
            out.add(line.toString());
            for(Core core : data.cores()){
                out.add("  core " + core.block().name() + " " + core.tileX() + "," + core.tileY() + " capacity " + core.storageCapacity
                    + " health " + core.health + " items " + items(core.items));
            }
        }
        for(int pos = 0; pos < tiles.size(); pos++){
            if(world.buildings().at(pos) instanceof Storage storage && storage.tile() == pos){
                out.add("storage " + storage.block().name() + " " + storage.tileX() + "," + storage.tileY() + " linked "
                    + (storage.linkedCore == null ? "-" : storage.linkedCore.tileX() + "," + storage.linkedCore.tileY()) + " items " + items(storage.items));
            }
        }
        out.add("next id " + world.ids().peek());
        out.add("rand " + world.rand().getState(0) + " " + world.rand().getState(1));
        return out;
    }

    record TeamDataView(List<Core> cores, int[] enemies){
        TeamDataView(com.xpdustry.shard.sim.team.TeamData data){
            this(data.cores(), data.coreEnemies());
        }
    }

    static String items(mindustry.world.modules.ItemModule items){
        var out = new StringJoiner(",", "[", "]");
        for(var item : content.items()) if(items.get(item) != 0) out.add(item.name + "=" + items.get(item));
        return out.toString();
    }

    static String items(ItemModule items){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < items.length(); id++) if(items.get(id) != 0) out.add(Worlds.EPOCH.item(id).name() + "=" + items.get(id));
        return out.toString();
    }

    /** Vanilla's entity id counter. */
    static final class CoreIds{
        static int lastId(){
            try{
                var field = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
                field.setAccessible(true);
                return field.getInt(null);
            }catch(ReflectiveOperationException e){
                throw new IllegalStateException(e);
            }
        }
    }
}
