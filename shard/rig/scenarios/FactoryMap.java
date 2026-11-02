import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.maps.Map;
import mindustry.io.*;

import static mindustry.Vars.*;

/**
 * Writes factory.msav, the map of shard-factory.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/FactoryMap.java rig/scenarios/factory.msav
 *
 * The W4.1 rig map: a core, and an air factory facing east at (14, 10) on its first plan (flare), powered by a power
 * source under it and stocked with 300 silicon and 300 lead. Units build ten times as fast, and the cap is 100.
 */
public class FactoryMap{
    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(40, 40);
        world.tile(8, 8).setBlock(Blocks.coreShard, Team.sharded, 0);
        world.tile(14, 10).setBlock(Blocks.airFactory, Team.sharded, 0);
        world.tile(13, 8).setBlock(Blocks.powerSource, Team.sharded, 0);
        world.build(14, 10).items.add(Items.silicon, 300);
        world.build(14, 10).items.add(Items.lead, 300);
        Vanilla.runPosted();
        state.map = new Map(StringMap.of("name", "factory", "author", "W4.1", "description", "An air factory, powered and stocked, by a core"));
        state.rules.waves = false;
        state.rules.modeName = "sandbox";
        state.rules.unitBuildSpeedMultiplier = 10f;
        // the flares it builds before alpha picks mono would otherwise fill the core's cap of 8, and a held ninth
        // flare stops the factory for good
        state.rules.unitCap = 100;
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0]);
    }
}
