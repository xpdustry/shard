import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.mod.data.*;
import mindustry.type.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Writes patches.msav, the map of shard-patches.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/PatchesMap.java rig/scenarios/patches.msav
 *
 * W5.4's L5 map: 40x30 stone in survival, no waves, no loadout, no generation filters. Team sharded's core-shard at
 * (8, 15); a mechanical drill on copper ore at (14..15, 15..16) dumping into four conveyors (13..10, 15) that face
 * west into the core. The map carries a data patch, made with vanilla's editor format ({@code PatchAsset}), that makes
 * the drill and the belt faster ({@code block.mechanical-drill.drillTime: 200}, {@code block.conveyor.speed: 0.08}), and
 * an embedded sprite asset ({@code shard-mark.png}, a 1x1 PNG), both written into the file ({@code embedAssets}).
 */
public class PatchesMap{
    static final String PATCH = "{\"name\": \"faster\", \"block.mechanical-drill.drillTime\": 200, \"block\": {\"conveyor\": {\"speed\": 0.08}}}";
    static final byte[] SPRITE = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==");

    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(40, 30);
        world.tile(8, 15).setBlock(Blocks.coreShard, Team.sharded, 0);
        world.build(8, 15).items.clear();
        for(int x = 14; x <= 15; x++){
            for(int y = 15; y <= 16; y++) world.tile(x, y).setOverlay(Blocks.oreCopper);
        }
        world.tile(14, 15).setBlock(Blocks.mechanicalDrill, Team.sharded, 0);
        for(int x = 10; x <= 13; x++) world.tile(x, 15).setBlock(Blocks.conveyor, Team.sharded, 2);
        Vanilla.runPosted();
        state.map = new Map(StringMap.of("name", "patches", "author", "W5.4", "genfilters", "[]", "description",
            "A data patch that speeds a drill and its belt, and an embedded sprite"));
        state.rules.waves = false;
        state.rules.waveTimer = false;
        state.rules.loadout = new Seq<ItemStack>();
        state.rules.modeName = "survival";
        var image = new ImageAsset();
        image.setPath("shard-mark.png");
        image.updateData(SPRITE);
        state.data.load(Seq.with(new PatchAsset(PATCH), image));
        MapIO.writeMap(new Fi(args[0]), state.map, true);
        System.out.println("wrote " + args[0]);
    }
}
