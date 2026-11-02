import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.world.blocks.logic.*;

import static mindustry.Vars.*;

/**
 * Writes sync-routers.msav, the map of sync-routers.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/SyncRoutersMap.java rig/scenarios/sync-routers.msav
 *
 * The W2.13 rig map: a router field fed by item sources, storage for block snapshots, a world processor that builds a 50x5 router grid from the start (routers: Shard can't stream a live-placed block it doesn't port, such as a wall). */
public class SyncRoutersMap{
    static final String PROGRAM = String.join("\n",
        "set x 4",
        "set y 50",
        "setblock block @router x y @sharded 0",
        "op add x x 1",
        "jump 7 lessThan x 54",
        "set x 4",
        "op add y y 1",
        "wait 0.25",
        "jump 2 lessThan y 55",
        "stop");
    /** Each field source gets an item, so the field fills (it has no sink) from the world's first ticks: as both servers run a hosted world from the start. */
    static final String FEED = String.join("\n",
        "set y 20",
        "op sub i y 20",
        "lookup item it i",
        "getblock building b 19 y",
        "control config b it 0 0 0",
        "op add y y 1",
        "jump 1 lessThan y 30",
        "stop");

    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(64, 64);
        var items = new mindustry.type.Item[]{Items.copper, Items.lead, Items.graphite, Items.silicon, Items.titanium, Items.metaglass, Items.sand, Items.coal, Items.scrap, Items.thorium};
        place(Blocks.coreShard, 8, 8);
        for(int y = 20; y < 30; y++){
            place(Blocks.itemSource, 19, y);
            for(int x = 20; x < 40; x++) place(Blocks.router, x, y);
        }
        for(int x = 4; x < 60; x += 2) place(Blocks.container, x, 36);
        for(int i = 0; i < 6; i++){
            place(Blocks.itemSource, 4 + i * 2, 35);
            world.build(4 + i * 2, 35).configureAny(items[i]);
        }
        for(int x = 5; x < 60; x += 3) place(Blocks.vault, x, 40);
        // a world processor takes code only from the editor, or with this rule
        state.rules.allowEditWorldProcessors = true;
        place(Blocks.worldProcessor, 2, 60);
        world.build(2, 60).configureAny(LogicBlock.compress(PROGRAM, new Seq<>()));
        place(Blocks.worldProcessor, 3, 60);
        world.build(3, 60).configureAny(LogicBlock.compress(FEED, new Seq<>()));
        Vanilla.runPosted();
        state.map = new Map(StringMap.of("name", "routers", "author", "W2.13", "description", "A router field that fills and jams once a player is in, storage, and a grid-building world processor"));
        state.rules.allowEditWorldProcessors = false;
        state.rules.waves = false;
        state.rules.modeName = "sandbox";
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0]);
    }

    static void place(mindustry.world.Block block, int x, int y){
        world.tile(x, y).setBlock(block, Team.sharded, 0);
    }
}
