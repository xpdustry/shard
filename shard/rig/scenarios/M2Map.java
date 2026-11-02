import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.io.*;
import mindustry.maps.Map;

import static mindustry.Vars.*;

/**
 * Writes m2.msav, the map of m2.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/M2Map.java rig/scenarios/m2.msav
 *
 * Fortress as M2OracleTest hosts it, generation filters run, then frozen: a default map's filters scatter ores and
 * boulders from a seed the host draws, so each server that hosts fortress puts its ores elsewhere, and the base the
 * oracle builds on copper (118, 79) and coal (119, 75) would stand on bare rock. This writes the tiles the oracle's
 * hosting made, with every tag of fortress but `genfilters`, which is empty, so hosting m2.msav leaves them as they are.
 */
public class M2Map{
    public static void main(String[] args) throws Exception{
        Vanilla.host("fortress");
        if(world.tile(118, 79).drop() != mindustry.content.Items.copper || world.tile(119, 75).drop() != mindustry.content.Items.coal){
            throw new IllegalStateException("fortress's ores aren't where M2OracleTest builds");
        }
        var tags = new StringMap(state.map.tags);
        tags.put("name", "m2");
        tags.put("author", "W5.6");
        tags.put("genfilters", "[]");
        tags.put("description", "M2: fortress with its generated ores frozen, for m2.rig: drills, a line into the core, combustion generators and a lancer");
        state.map = new Map(tags);
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0]);
    }
}
