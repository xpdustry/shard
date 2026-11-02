import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.type.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import static mindustry.Vars.*;

/**
 * Writes m0.msav, the map of m0.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/M0Map.java rig/scenarios/m0.msav
 *
 * 100x50 stone in survival. Team sharded's core-shard at (12, 25) with copper and lead (the loadout, and the core's own
 * items for a server that doesn't apply the loadout), an unloader east of it set to copper; the tiles alpha builds on
 * are left open: a conveyor line (15..19, 25) and a router (20, 25) with duos on three sides. A micro processor at
 * (17, 28) is linked by name ("conveyor1") to the empty tile (17, 25), where alpha's third conveyor goes, and turns
 * whatever stands there off for a second and on for two. A lancer at (22, 29) and six solar panels at (25..30, 33)
 * with a battery at (25, 34), which they charge, stand apart, unpowered until alpha places a power node at (24, 31):
 * the battery then gives the lancer its full 6 power a tick, without which it turns too slowly to fire. Crux's ground
 * spawn is at (88, 25); every wave is two daggers, the first 120 s after the map is hosted, then every 90 s.
 */
public class M0Map{
    static final String PROGRAM = String.join("\n",
        "control enabled conveyor1 0",
        "wait 1",
        "control enabled conveyor1 1",
        "wait 2");

    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(100, 50);
        place(Blocks.coreShard, 12, 25);
        var core = world.build(12, 25);
        core.items.clear();
        core.items.add(Items.copper, 1000);
        core.items.add(Items.lead, 200);
        place(Blocks.unloader, 14, 25);
        world.build(14, 25).configureAny(Items.copper);
        place(Blocks.microProcessor, 17, 28);
        world.build(17, 28).configureAny(mindustry.world.blocks.logic.LogicBlock.compress(PROGRAM, Seq.with(new LogicLink(0, -3, "conveyor1", true))));
        place(Blocks.lancer, 22, 29);
        for(int x = 25; x <= 30; x++) place(Blocks.solarPanel, x, 33);
        place(Blocks.battery, 25, 34);
        world.tile(88, 25).setOverlay(Blocks.spawn);
        Vanilla.runPosted();

        // no generation filters: without the tag, hosting scatters random boulders and ores (Maps.readFilters)
        state.map = new Map(StringMap.of("name", "m0", "author", "W2.22", "genfilters", "[]", "description",
            "M0: a player's conveyor line to three duos, a processor that switches it, a lancer on solar power, and two daggers a wave"));
        state.rules.waves = true;
        state.rules.waveTimer = true;
        state.rules.initialWaveSpacing = 120 * 60f;
        state.rules.waveSpacing = 90 * 60f;
        state.rules.loadout = ItemStack.list(Items.copper, 1000, Items.lead, 200);
        state.rules.spawns = Seq.with(new SpawnGroup(UnitTypes.dagger){{
            end = 100;
            unitAmount = 2;
            unitScaling = 1000f;
        }});
        state.rules.modeName = "survival";
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0]);
    }

    static void place(mindustry.world.Block block, int x, int y){
        world.tile(x, y).setBlock(block, Team.sharded, 0);
    }
}
