import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.maps.Map;
import mindustry.io.*;

import static mindustry.Vars.*;

/**
 * Writes fog.msav, the map of shard-fog.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/FogMap.java rig/scenarios/fog.msav
 *
 * The W4.3 rig map, fog on, 200 by 60: a sharded core in the west and a crux core in the east, 180 tiles apart (a
 * core sees 46). Each team has a dagger five tiles inside the other core's fog radius, which that core's team sees,
 * and one in the middle of the map, 20 tiles off the axis and 90 from either core, which nobody else sees (a dagger
 * sees 21.75).
 */
public class FogMap{
    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(200, 60);
        world.tile(10, 30).setBlock(Blocks.coreShard, Team.sharded, 0);
        world.tile(190, 30).setBlock(Blocks.coreShard, Team.crux, 0);
        int near = Blocks.coreShard.fogRadius - 5;
        // near: in sight of the enemy core, out of a dagger's 7.5-tile reach
        UnitTypes.dagger.spawn(Team.crux, (10 + near) * 8f, 30 * 8f);
        UnitTypes.dagger.spawn(Team.sharded, (190 - near) * 8f, 30 * 8f);
        // far: in the middle, off the axis
        UnitTypes.dagger.spawn(Team.crux, 100 * 8f, 50 * 8f);
        UnitTypes.dagger.spawn(Team.sharded, 100 * 8f, 10 * 8f);
        Vanilla.runPosted();
        state.map = new Map(StringMap.of("name", "fog", "author", "W4.3", "description", "Two cores in fog, a dagger of each team near the other's core and one in the middle"));
        state.rules.waves = false;
        state.rules.fog = true;
        state.rules.staticFog = true;
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0] + ", core fog radius " + Blocks.coreShard.fogRadius + ", near daggers " + near + " tiles from the enemy core");
    }
}
