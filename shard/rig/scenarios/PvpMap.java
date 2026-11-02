import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.maps.Map;
import mindustry.io.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import static mindustry.Vars.*;

/**
 * Writes pvp.msav, the map of pvp.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/PvpMap.java rig/scenarios/pvp.msav
 *
 * The W5.3 rig map, 60 by 30, hosted as PvP: a sharded core in the west (10, 15) and a crux core in the east (50, 15).
 * A derelict switch in the middle (30, 15), off, which any team may configure (derelict buildings are interactable by
 * everyone), and a world processor of sharded's (30, 5; privileged, so a team's clean-up spares it) that waits for
 * it: once a client turns the switch on, the processor sets off an explosion of team sharded over the crux core, which
 * falls, and the game is over.
 */
public class PvpMap{
    static final String CODE = """
        getblock building sw 30 15
        sensor on sw @enabled
        jump 0 notEqual on true
        explosion @sharded 50 15 4 1000000 true true false true
        wait 1000
        """;

    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(60, 30);
        world.tile(10, 15).setBlock(Blocks.coreShard, Team.sharded, 0);
        world.tile(50, 15).setBlock(Blocks.coreShard, Team.crux, 0);
        world.tile(30, 15).setBlock(Blocks.switchBlock, Team.derelict, 0);
        world.build(30, 15).configureAny(false);
        // sharded's, not derelict: derelict buildings never update (BuildingComp.allowUpdate)
        world.tile(30, 5).setBlock(Blocks.worldProcessor, Team.sharded, 0);
        ((LogicBuild)world.build(30, 5)).updateCode(CODE);
        Vanilla.runPosted();
        state.map = new Map(StringMap.of("name", "pvp", "author", "W5.3", "description", "Two cores; a switch in the middle sets off an explosion over the crux core"));
        state.rules.waves = false;
        state.rules.pvp = true;
        state.rules.attackMode = true;
        // Vanilla.emptyWorld turns it off for the oracle; the map keeps its rules
        state.rules.canGameOver = true;
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0] + ", switch " + world.build(30, 15).config() + ", processor code " + ((LogicBuild)world.build(30, 5)).code.length() + " chars");
    }
}
