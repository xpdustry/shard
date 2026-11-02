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
 * Writes activebench.msav, the map of the five-world allocation bench (bench.py), with headless vanilla (the oracle; no
 * client). From shard/, after {@code ./gradlew :tools:oracle:jar}:
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     tools/bench/ActiveMap.java tools/bench/activebench.msav
 *
 * 300x80 stone in survival: team sharded's core-shard at (12, 25) with copper and lead; a
 * world processor at (4, 4) that places a router every quarter second across (10..79, 40..44), over and over; an item
 * source feeding copper down a 49-tile conveyor into a container at (60, 20); an unloader, a micro processor switching
 * a conveyor tile, and a lancer with solar panels and a battery. Crux's ground spawn is at (280, 25); every wave is 12
 * daggers, the first after 10 s, then every 10 s. The name, author and description below are the ones the committed
 * map was written with; change them only together with the map.
 */
public class ActiveMap{
    static final String PROGRAM = String.join("\n",
        "control enabled conveyor1 0",
        "wait 1",
        "control enabled conveyor1 1",
        "wait 2");

    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(300, 80);
        place(Blocks.coreShard, 12, 25);
        var core = world.build(12, 25);
        core.items.clear();
        core.items.add(Items.copper, 1000);
        core.items.add(Items.lead, 200);
        state.rules.allowEditWorldProcessors = true;
        place(Blocks.worldProcessor, 4, 4);
        world.build(4, 4).configureAny(mindustry.world.blocks.logic.LogicBlock.compress(String.join("\n",
            "set x 10", "set y 40", "setblock block @router x y @sharded 0",
            "op add x x 1", "wait 0.25", "jump 2 lessThan x 80", "set x 10", "op add y y 1",
            "jump 2 lessThan y 45", "set y 40", "jump 2 always 0 0"), new Seq<>()));
        state.rules.allowEditWorldProcessors = false;
        place(Blocks.itemSource, 10, 20);
        world.build(10, 20).configureAny(Items.copper);
        for(int x = 11; x < 60; x++) place(Blocks.conveyor, x, 20);
        place(Blocks.container, 60, 20);
        place(Blocks.unloader, 14, 25);
        world.build(14, 25).configureAny(Items.copper);
        place(Blocks.microProcessor, 17, 28);
        world.build(17, 28).configureAny(mindustry.world.blocks.logic.LogicBlock.compress(PROGRAM, Seq.with(new LogicLink(0, -3, "conveyor1", true))));
        place(Blocks.lancer, 22, 29);
        for(int x = 25; x <= 30; x++) place(Blocks.solarPanel, x, 33);
        place(Blocks.battery, 25, 34);
        world.tile(280, 25).setOverlay(Blocks.spawn);
        Vanilla.runPosted();

        // no generation filters: without the tag, hosting scatters random boulders and ores (Maps.readFilters)
        state.map = new Map(StringMap.of("name", "activebench", "author", "W2.22", "genfilters", "[]", "description",
            "M0: a player's conveyor line to three duos, a processor that switches it, a lancer on solar power, and two daggers a wave"));
        state.rules.waves = true;
        state.rules.waveTimer = true;
        state.rules.initialWaveSpacing = 10 * 60f;
        state.rules.waveSpacing = 10 * 60f;
        state.rules.loadout = ItemStack.list(Items.copper, 1000, Items.lead, 200);
        state.rules.spawns = Seq.with(new SpawnGroup(UnitTypes.dagger){{
            end = 100;
            unitAmount = 12;
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
