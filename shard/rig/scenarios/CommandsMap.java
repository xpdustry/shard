import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.maps.Map;
import mindustry.io.*;

import static mindustry.Vars.*;

/**
 * Writes commands.msav, the map of shard-commands.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/CommandsMap.java rig/scenarios/commands.msav
 *
 * The W4.2 rig map: a core, four daggers and two flares of its team beside it, and a wall of stone across the map at
 * x = 28 with a gap at y 18-22, which the ground units must walk through.
 */
public class CommandsMap{
    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(48, 40);
        for(int y = 0; y < 40; y++){
            if(y < 18 || y > 22) world.tile(28, y).setBlock(Blocks.stoneWall);
        }
        world.tile(8, 20).setBlock(Blocks.coreShard, Team.sharded, 0);
        for(int i = 0; i < 4; i++) UnitTypes.dagger.spawn(Team.sharded, (14 + (i % 2) * 2) * 8f, (18 + (i / 2) * 3) * 8f);
        UnitTypes.flare.spawn(Team.sharded, 18 * 8f, 18 * 8f);
        UnitTypes.flare.spawn(Team.sharded, 18 * 8f, 22 * 8f);
        Vanilla.runPosted();
        state.map = new Map(StringMap.of("name", "commands", "author", "W4.2", "description", "Four daggers and two flares by a core, a wall with a gap"));
        state.rules.waves = false;
        state.rules.modeName = "sandbox";
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0]);
    }
}
