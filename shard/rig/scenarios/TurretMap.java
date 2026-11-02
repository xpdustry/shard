import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.maps.Map;
import mindustry.io.*;

import static mindustry.Vars.*;

/**
 * Writes turret.msav, the map of shard-turret.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/TurretMap.java rig/scenarios/turret.msav
 *
 * The W5.7 rig map: a core at (8, 8); a duo at (14, 8) holding 20 rounds of copper and fed nothing, with no enemy
 * anywhere, so it turns and fires only when a player does; and a dagger standing on a payload conveyor at (8, 16)
 * that faces east, which shard-turret.rig no longer uses (see its header).
 */
public class TurretMap{
    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(40, 40);
        world.tile(8, 8).setBlock(Blocks.coreShard, Team.sharded, 0);
        world.tile(14, 8).setBlock(Blocks.duo, Team.sharded, 0);
        for(int i = 0; i < 10; i++) world.build(14, 8).handleItem(null, Items.copper);
        world.tile(8, 16).setBlock(Blocks.payloadConveyor, Team.sharded, 0);
        UnitTypes.dagger.spawn(Team.sharded, 8 * 8f, 16 * 8f);
        Vanilla.runPosted();
        state.map = new Map(StringMap.of("name", "turret", "author", "W5.7", "description", "A duo with 20 rounds and a dagger on a payload conveyor, by a core"));
        state.rules.waves = false;
        state.rules.modeName = "sandbox";
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0] + ", the duo holds " + ((mindustry.world.blocks.defense.turrets.Turret.TurretBuild)world.build(14, 8)).totalAmmo);
    }
}
