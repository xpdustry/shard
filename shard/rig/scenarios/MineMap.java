import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.type.*;

import static mindustry.Vars.*;

/**
 * Writes mine.msav, the map of shard-mine.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/MineMap.java rig/scenarios/mine.msav
 *
 * W3.7's L5 map: 64x40 stone in survival, no waves, no loadout and no generation filters, so both servers host the
 * same tiles with an empty core. Team sharded's core-shard at (10, 33); copper ore by it at (14..16, 36..38), in its
 * mineTransferRange (220 units); copper ore at (58..62, 36..39), in the top-right corner, 50 tiles from it; and a
 * container at (58, 33) beside that ore. A grounded unit stops one tile short of the map's right and top edges, so a
 * player walking up, then right, ends at world (504, 312), 5 tiles from the far ore, and walking left along the top row
 * at (0, 312), 12 tiles from the core.
 */
public class MineMap{
    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(64, 40);
        world.tile(10, 33).setBlock(Blocks.coreShard, Team.sharded, 0);
        world.build(10, 33).items.clear();
        for(int x = 14; x <= 16; x++){
            for(int y = 36; y <= 38; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);
        }
        for(int x = 58; x <= 62; x++){
            for(int y = 36; y <= 39; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);
        }
        world.tile(58, 33).setBlock(Blocks.container, Team.sharded, 0);
        Vanilla.runPosted();
        // no generation filters: without the tag, hosting scatters random boulders and ores (Maps.readFilters)
        state.map = new Map(StringMap.of("name", "mine", "author", "W3.7", "genfilters", "[]", "description",
            "Copper by the core and out of its reach, and a container"));
        state.rules.waves = false;
        state.rules.waveTimer = false;
        state.rules.loadout = new Seq<ItemStack>();
        state.rules.modeName = "survival";
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0]);
    }
}
