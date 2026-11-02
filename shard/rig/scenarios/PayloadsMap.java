import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.maps.Map;
import mindustry.io.*;

import static mindustry.Vars.*;

/**
 * Writes payloads.msav, the map of shard-payloads.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/PayloadsMap.java rig/scenarios/payloads.msav
 *
 * The W3.9 rig map: a core, a mega for a player to take over, a dagger two tiles above it, and a container of
 * 50 copper three and a half tiles below, within the mega's reach.
 */
public class PayloadsMap{
    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(40, 40);
        world.tile(8, 8).setBlock(Blocks.coreShard, Team.sharded, 0);
        // a 2x2 block's origin is its bottom left tile: (14, 8) to (15, 9), its centre (116, 68)
        world.tile(14, 8).setBlock(Blocks.container, Team.sharded, 0);
        world.build(14, 8).items.add(Items.copper, 50);
        UnitTypes.mega.spawn(Team.sharded, 116f, 96f);
        UnitTypes.dagger.spawn(Team.sharded, 116f, 112f);
        Vanilla.runPosted();
        state.map = new Map(StringMap.of("name", "payloads", "author", "W3.9", "description", "A mega, a dagger and a container of copper by a core"));
        state.rules.waves = false;
        state.rules.modeName = "sandbox";
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0]);
    }
}
